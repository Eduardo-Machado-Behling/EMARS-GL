package mars.tools;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.DebugGL3;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.FPSAnimator;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Observable;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import mars.Globals;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;

public class GameStationOpenGL extends AbstractMarsToolAndApplication {

    // GUI components
    private JTextArea displayArea;
    private JLabel statusLabel;
    private boolean isFocused;

    private int width  = 512;
    private int height = 256;
    private PixelBufferCanvas canvas;
    private int fps = 0;

    private JTextArea logArea; // New log area

    private final int keyPressAddress = 0xffff0000;
    private int keyPressAmount        = 0;

    private boolean lockKeys                                        = false;
    private final Queue<javafx.util.Pair<KeyEvent, KeyType>> buffer = new ArrayDeque<>();
    private final Set<Integer> pressedKeys                          = new HashSet<>(); // Track pressed keys

    private final int keyReleaseAddress = 0xffff0010;
    private int keyReleaseAmount        = 0;

    static private final int openGLBaseAddress   = 0x10080000;
    static private final int openGLRedrawAddress = 0x1007fffc;
    static private final int openGLHighAddress   = 0x100C0010;
    static final int OPENGL_WRITABLE_DATA_AMOUNT = openGLHighAddress - openGLBaseAddress;

    enum KeyType {
        PRESS,
        RELEASE
    }

    /**
     * Constructor sets up the tool's basic properties
     */
    public GameStationOpenGL() {
        super( "GameStation OpenGl", "Simulate GPU" );
        isFocused = false;
    }

    /**
     * Returns the name that will appear in MARS Tools menu
     */
    public String getName() {
        return "Game Station GPU";
    }

    /**
     * Set up our tool to observe memory
     */
    protected void addAsObserver() {
        // Special case: baseAddress<0 means we're in kernel memory (0x80000000 and
        // up) and most likely in memory map address space (0xffff0000 and up).  In
        // this case, we need to make sure the high address does not drop off the
        // high end of 32 bit address space.  Highest allowable word address is
        // 0xfffffffc, which is interpreted in Java int as -4.
        addAsObserver( openGLRedrawAddress, openGLRedrawAddress );
        addAsObserver( keyPressAddress, keyPressAddress );
        addAsObserver( keyReleaseAddress, keyReleaseAddress );
    }

    @Override
    protected void processMIPSUpdate( Observable memory,
                                      AccessNotice accessNotice ) {
        MemoryAccessNotice mem = (MemoryAccessNotice) accessNotice;
        if ( accessNotice.getAccessType() == AccessNotice.WRITE ) {

            switch ( mem.getAddress() ) {
                case openGLRedrawAddress:
                    try {
                        canvas.drawCall( Memory.getInstance().getWordNoNotify( openGLBaseAddress ) );
                    } catch ( AddressErrorException e ) {
                        e.printStackTrace();
                    }
                    break;

                case keyReleaseAddress:
                    keyReleaseAmount = 0;
                    lockKeys         = false;
                    clearBuffer();
                    break;

                case keyPressAddress:
                    keyPressAmount = 0;
                    lockKeys       = false;
                    clearBuffer();
                    break;

                default:
                    break;
            }
        } else {
            switch ( mem.getAddress() ) {
                case keyReleaseAddress:
                    if ( keyReleaseAmount > 0 )
                        lockKeys = true;
                    break;

                case keyPressAddress:
                    fps++;
                    if ( keyPressAmount > 0 )
                        lockKeys = true;
                    break;

                default:
                    break;
            }
        }
    }

    private void clearBuffer() {
        while ( !buffer.isEmpty() ) {
            javafx.util.Pair<KeyEvent, KeyType> ev = buffer.poll();
            handleKeyEvent( ev.getKey(), ev.getValue() );
        }
    }

    @Override
    protected void updateDisplay() {
    }

    /**
     * Builds the main interface for the tool
     */
    protected JComponent buildMainDisplayArea() {
        System.out.println( "Build" );
        JPanel panel = new JPanel();
        panel.setLayout(
            new BoxLayout( panel, BoxLayout.Y_AXIS ) ); // Vertical BoxLayout

        // OpenGL Canvas
        JPanel firstRowPanel = new JPanel( new BorderLayout() );
        firstRowPanel.setBackground( Color.CYAN );

        GLProfile glp       = GLProfile.get( GLProfile.GL3 ); // Or try GLProfile.get(GLProfile.GL3) if needed
        GLCapabilities caps = new GLCapabilities( glp );
        canvas              = new PixelBufferCanvas( width, height, 60, caps ); // Pass capabilities to the constructor
        firstRowPanel.add( canvas, BorderLayout.CENTER );
        firstRowPanel.setBorder( BorderFactory.createEmptyBorder( 10, 10, 10, 10 ) );

        JPanel secondRowPanel = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
        logArea               = new JTextArea( 5, 40 );
        logArea.setEditable( false );
        logArea.setFont( new Font( Font.MONOSPACED, Font.PLAIN, 12 ) );
        JScrollPane scrollPanel = new JScrollPane( logArea ); // scrollPanel must contain logArea
        secondRowPanel.add( scrollPanel );

        // Create status label to show focus state
        JPanel thirdRowPanel = new JPanel( new FlowLayout( FlowLayout.LEFT ) ); // Or any other layout
        statusLabel          = new JLabel( "Click here and connect to enable keyboard" );
        statusLabel.setHorizontalAlignment( JLabel.CENTER );
        thirdRowPanel.add( statusLabel );

        // Add key listener to the panel
        setupKeyBindings( panel );
        // Add mouse listener to handle focus
        panel.addMouseListener( new MouseAdapter() {
            @Override
            public void mouseClicked( MouseEvent e ) {
                panel.requestFocusInWindow();
                isFocused = true;
                updateStatus();
            }
        } );

        // Add focus listeners
        panel.addFocusListener( new FocusAdapter() {
            @Override
            public void focusGained( FocusEvent e ) {
                isFocused = true;
                updateStatus();
            }

            @Override
            public void focusLost( FocusEvent e ) {
                isFocused = false;
                updateStatus();
            }
        } );

        panel.add( firstRowPanel );
        panel.add( secondRowPanel );
        panel.add( thirdRowPanel );

        setContentPane( panel );
        pack(); // Calculate size
        setLocationRelativeTo( null );
        panel.setFocusable( true );

        Timer timer = new Timer();
        timer.scheduleAtFixedRate( new FPSTask( this ), 0, 1000 );

        return panel;
    }

    private void setupKeyBindings( JPanel panel ) {
        InputMap inputMap   = panel.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW );
        ActionMap actionMap = panel.getActionMap();

        int[] keys = {
            KeyEvent.VK_0, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5,
            KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9,
            KeyEvent.VK_A, KeyEvent.VK_B, KeyEvent.VK_C, KeyEvent.VK_D, KeyEvent.VK_E, KeyEvent.VK_F,
            KeyEvent.VK_G, KeyEvent.VK_H, KeyEvent.VK_I, KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L,
            KeyEvent.VK_M, KeyEvent.VK_N, KeyEvent.VK_O, KeyEvent.VK_P, KeyEvent.VK_Q, KeyEvent.VK_R,
            KeyEvent.VK_S, KeyEvent.VK_T, KeyEvent.VK_U, KeyEvent.VK_V, KeyEvent.VK_W, KeyEvent.VK_X,
            KeyEvent.VK_Y, KeyEvent.VK_Z,
            KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_TAB,
            KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT,
            KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
            KeyEvent.VK_ESCAPE, KeyEvent.VK_DELETE, KeyEvent.VK_INSERT, KeyEvent.VK_HOME,
            KeyEvent.VK_END, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
            KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5,
            KeyEvent.VK_F6, KeyEvent.VK_F7, KeyEvent.VK_F8, KeyEvent.VK_F9, KeyEvent.VK_F10,
            KeyEvent.VK_F11, KeyEvent.VK_F12
        };

        // Bind each key for both PRESS and RELEASE events
        for ( int keyCode : keys ) {
            bindKey( panel, keyCode, KeyType.PRESS, true );
            bindKey( panel, keyCode, KeyType.RELEASE, false );
        }
    }

    private void bindKey( JPanel panel, int keyCode, KeyType type, boolean pressed ) {
        String actionName = "key_" + keyCode + "_" + ( pressed ? "pressed" : "released" );

        InputMap inputMap   = panel.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW );
        ActionMap actionMap = panel.getActionMap();

        inputMap.put( KeyStroke.getKeyStroke( keyCode, 0, !pressed ), actionName );
        actionMap.put( actionName, new AbstractAction() {
            @Override
            public void actionPerformed( ActionEvent e ) {
                KeyEvent keyEvent = new KeyEvent( panel, pressed ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                                                  System.currentTimeMillis(), 0, keyCode, (char) keyCode );
                if ( isFocused ) {
                    if ( !lockKeys ) {
                        handleKeyEvent( keyEvent, type );
                    } else {
                        buffer.add( new javafx.util.Pair<>( keyEvent, type ) );
                    }
                }
            }
        } );
    }

    public class FPSTask extends TimerTask {

        private GameStationOpenGL parent;

        FPSTask( GameStationOpenGL parent ) {
            this.parent = parent;
        }

        @Override
        public void run() {
            System.out.println( "fps: " + String.format( "- (%d)", parent.fps ) );
            fps = 0;
        }
    }

    /**
     * Handles a key press by triggering a MIPS interrupt
     */
    private void handleKeyEvent( KeyEvent e, KeyType t ) {
        try {
            if ( t == KeyType.PRESS ) {
                if ( pressedKeys.contains( e.getKeyCode() ) )
                    return;

                pressedKeys.add( e.getKeyCode() );
                if ( keyPressAmount < 15 ) {
                    Globals.memory.setByte( keyPressAddress + ++keyPressAmount, e.getKeyCode() );
                    Globals.memory.setByte( keyPressAddress, keyPressAmount );
                }
            } else {
                pressedKeys.remove( e.getKeyCode() );
                if ( keyReleaseAmount < 15 ) {
                    Globals.memory.setByte( keyReleaseAddress + ++keyReleaseAmount, e.getKeyCode() );
                    Globals.memory.setByte( keyReleaseAddress, keyReleaseAmount );
                }
            }

            SwingUtilities.invokeLater( () -> {
                logArea.append( String.format( "Key %s: %s (code: %d)\n", t.toString(),
                                               KeyEvent.getKeyText( e.getKeyCode() ),
                                               e.getKeyCode() ) );
                logArea.setCaretPosition( logArea.getDocument().getLength() );
            } );

        } catch ( AddressErrorException ex ) {
            logArea.append( "Error accessing memory: " + ex.getMessage() + "\n" );
        }
    }

    /**
     * Updates the status label based on focus and connection state
     */
    private void updateStatus() {
        if ( !isFocused ) {
            statusLabel.setText( "Click here to enable keyboard input" );
        } else if ( !isObserving() ) {
            statusLabel.setText( "Click 'Connect' to enable interrupts" );
        } else {
            statusLabel.setText( "Ready for keyboard input" );
        }
    }
    /**
     * Reset clears the display and memory
     */
    protected void reset() {
        logArea.setText( "" );
        isFocused = false;
        updateStatus();

        try {
            // Clear our memory-mapped I/O addresses
            Globals.memory.setWord( keyPressAddress, 0 );
            Globals.memory.setWord( keyReleaseAddress, 0 );

            keyPressAmount   = 0;
            keyReleaseAmount = 0;
            lockKeys         = false;
            pressedKeys.clear();
            buffer.clear();

            // Clear all pixels to black
            canvas.clear();
        } catch ( AddressErrorException ex ) {
            displayArea.append( "Error resetting memory: " + ex.getMessage() + "\n" );
        }
    }

    protected class PixelBufferCanvas
        extends GLCanvas implements GLEventListener {

        private int width;
        private int height;

        private final int[] vertexArrayId  = new int[1];
        private final int[] vertexBufferId = new int[1];
        private final int[] instanceId     = new int[1];

        private ByteBuffer buffer;

        private int instanceAmount        = 1;
        private boolean bufferNeedsUpdate = false;

        private volatile boolean initialized = false;
        private volatile boolean contextLost = false;

        private long frameCount = 0;
        private FPSAnimator animator;

        private final ShadersUtils shadersUtil   = new ShadersUtils();
        private final TextureManager textManager = new TextureManager();

        public PixelBufferCanvas( int width, int height, int fps, GLCapabilities caps ) {
            super( caps );
            this.width            = width;
            this.height           = height;
            this.vertexArrayId[0] = -1;

            if ( fps != 0 ) {
                this.animator = new FPSAnimator( this, fps );
                animator.start();
            }

            // Force the canvas size to be fixed
            Dimension size = new Dimension( width, height );
            setPreferredSize( size );
            setMinimumSize( size );
            setMaximumSize( size );

            // Add our GLEventListener
            addGLEventListener( this );

            // Add a component listener to detect visibility changes
            addComponentListener( new ComponentAdapter() {
                @Override
                public void componentShown( ComponentEvent e ) {
                    System.out.println( "Canvas shown" );
                }

                @Override
                public void componentHidden( ComponentEvent e ) {
                    System.out.println( "Canvas hidden" );
                }

                @Override
                public void componentResized( ComponentEvent e ) {
                    System.out.println( "Canvas resized to: " + getWidth() + "x" + getHeight() );
                }
            } );
        }

        public void clear() {
            drawCall( 0 );
        }

        public void drawCall( int value ) {

            instanceAmount    = value;
            bufferNeedsUpdate = true;
        }

        @Override
        public void init( GLAutoDrawable drawable ) {
            System.out.println( "OpenGL Init called" );

            GL3 gl = drawable.getGL().getGL3();
            gl.glEnable( GL3.GL_DEBUG_OUTPUT );
            gl.glEnable( GL3.GL_DEBUG_OUTPUT_SYNCHRONOUS );
            try {
                // Enable error checking
                gl = new DebugGL3( gl );
                drawable.setGL( gl );

                createVertexBuffer( gl );
                createInstanceBuffer( gl );
                shadersUtil.init( gl );
                textManager.init( gl );

                initialized = true;
                contextLost = false;
                System.out.println( "OpenGL initialization successful" );

            } catch ( GLException e ) {
                System.err.println( "OpenGL initialization failed: " + e.getMessage() );
                e.printStackTrace();
                contextLost = true;
            }
        }

        @Override
        public void display( GLAutoDrawable drawable ) {
            frameCount++;
            if ( frameCount % 60 == 0 ) { // Log every 60 frames
                // System.out.println( "Frame " + frameCount + " - Canvas size: " + getWidth() + "x" + getHeight() );
            }

            if ( !initialized || contextLost ) {
                System.out.println( "Skipping display - Context not ready" );
                return;
            }

            GL3 gl = drawable.getGL().getGL3();
            shadersUtil.use( gl, 0 );

            if ( bufferNeedsUpdate ) {
                updateBuffer( gl );
                bufferNeedsUpdate = false;
            }

            textManager.populateTexture( gl );
            shadersUtil.setTexture( gl, textManager.activateTexture( gl ) );

            try {
                // full area
                gl.glClearColor( 0.2f, 0.2f, 0.2f, 1.0f );
                gl.glClear( GL.GL_COLOR_BUFFER_BIT );

                gl.glBindVertexArray( vertexArrayId[0] );
                gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, instanceId[0] );
                gl.glDrawArraysInstanced( GL3.GL_TRIANGLE_FAN, 0, 4, instanceAmount );
                gl.glBindVertexArray( 0 );
                gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, 0 );

                int error = gl.glGetError();
                if ( error != GL.GL_NO_ERROR ) {
                    System.err.println( "OpenGL error in display: 0x" + Integer.toHexString( error ) );
                }

            } catch ( GLException e ) {
                System.err.println( "Display error: " + e.getMessage() );
                e.printStackTrace();
                contextLost = true;
            }
        }

        private void updateBuffer( GL3 gl ) {
            gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, instanceId[0] );
            buffer = gl.glMapBuffer( GL3.GL_ARRAY_BUFFER, GL3.GL_MAP_WRITE_BIT | GL3.GL_READ_WRITE );

            formatBuffer( buffer );

            gl.glUnmapBuffer( GL3.GL_ARRAY_BUFFER );
            gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, 0 );
        }

        private void formatBuffer( ByteBuffer buffer ) {
            buffer.clear();
            int addr = GameStationOpenGL.openGLBaseAddress + 4;
            for ( int i = 0; i < instanceAmount; i++ ) {
                int xy;
                int wh;
                int rotype;
                int color;

                // System.out.print( "addr = " + Integer.toHexString( addr ) + " | " );
                try {
                    xy = Memory.getInstance().getWordNoNotify( addr );
                    addr += 4;
                    wh = Memory.getInstance().getWordNoNotify( addr );
                    addr += 4;
                    rotype = Memory.getInstance().getWordNoNotify( addr );
                    addr += 4;
                    color = Memory.getInstance().getWordNoNotify( addr );
                    addr += 4;
                } catch ( AddressErrorException e ) {
                    e.printStackTrace();
                    return;
                }

                int x = xy >> 16;
                int y = xy & 0xffff;

                int w = wh >> 16;
                int h = wh & 0xffff;

                int type     = rotype >> 16;
                int rotation = rotype & 0xffff;

                float r = ( ( color >> 24 ) & 0xff ) / 255.0f;
                float g = ( ( color >> 16 ) & 0xff ) / 255.0f;
                float b = ( ( color >> 8 ) & 0xff ) / 255.0f;
                float a = ( color & 0xff ) / 255.0f;

                float fx  = ( x * 2.0f + w ) / width - 1.0f;
                float fy  = -( y * 2.0f + h ) / height + 1.0f;
                float fw  = w * 2.0f / width;
                float fh  = h * 2.0f / height;
                float rot = (float) ( Math.PI * ( rotation / 180.0f ) );

                // System.out.print( "x = " + x + " | " );
                // System.out.print( "y = " + y + " | " );
                // System.out.print( "w = " + w + " | " );
                // System.out.print( "h = " + h + " | " );
                // System.out.print( "rot = " + rot + " | " );
                // System.out.print( "r =" + r + " | " );
                // System.out.print( "g = " + g + " | " );
                // System.out.print( "b =" + b + " | " );
                // System.out.print( "a = " + a + " | " );
                // System.out.print( "text = " + Integer.toHexString( color ) + " | " );
                // System.out.println(); // New line for clarity

                buffer.putFloat( fx );
                buffer.putFloat( fy );
                buffer.putFloat( fw );
                buffer.putFloat( fh );
                buffer.putFloat( rot );
                buffer.putFloat( r );
                buffer.putFloat( g );
                buffer.putFloat( b );
                buffer.putFloat( a );
                if ( type == 0 ) {
                    buffer.putFloat( -1 );
                } else {
                    int textAddr = color;
                    buffer.putFloat( (float) textManager.push( textAddr ) );
                }
            }
        }

        @Override
        public void reshape( GLAutoDrawable drawable, int x, int y, int width,
                             int height ) {
            System.out.println( "Reshape called: " + width + "x" + height );
            GL3 gl = drawable.getGL().getGL3();

            this.width  = width;
            this.height = height;
            createVertexBuffer( gl );
            gl.glViewport( 0, 0, width, height );
        }

        @Override
        public void dispose( GLAutoDrawable drawable ) {
            System.out.println( "Dispose called" );
            if ( !initialized ) {
                return;
            }

            animator.stop();
            GL3 gl = drawable.getGL().getGL3();
            gl.glDeleteBuffers( 1, vertexArrayId, 0 );
            gl.glDeleteBuffers( 1, vertexBufferId, 0 ); // if you need to delete the vertex buffer
            gl.glDeleteBuffers( 1, instanceId, 0 );
            textManager.dispose( gl );
            initialized = false;
        }

        private void createVertexBuffer( GL3 gl ) {
            if ( vertexArrayId[0] == -1 ) {
                gl.glGenVertexArrays( 1, vertexArrayId, 0 );
            }

            gl.glBindVertexArray( vertexArrayId[0] );

            // clang-format off
            float[] vertices = {
                -0.5f, -0.5f,  // Bottom left
                -0.5f,  0.5f,  // Top left
                0.5f ,  0.5f,  // Top right
                0.5f , -0.5f   // Bottom right
            };
            // clang-format on

            FloatBuffer vertexBufferData = Buffers.newDirectFloatBuffer( vertices );
            gl.glGenBuffers( 1, vertexBufferId, 0 );
            gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, vertexBufferId[0] );
            gl.glBufferData( GL3.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, vertexBufferData, GL3.GL_STATIC_DRAW );
            gl.glVertexAttribPointer( 0, 2, GL3.GL_FLOAT, false, 2 * Float.BYTES, 0 );
            gl.glEnableVertexAttribArray( 0 );
        }

        private void createInstanceBuffer( GL3 gl ) {
            gl.glGenBuffers( 1, instanceId, 0 );
            gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, instanceId[0] );
            gl.glBufferData(
                GL3.GL_ARRAY_BUFFER,
                GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT,
                null,
                GL3.GL_DYNAMIC_DRAW );

            defineAttributes( gl, instanceId[0] );
        }

        private void defineAttributes( GL3 gl, int instanceBuffer ) {
            gl.glBindVertexArray( vertexArrayId[0] );
            gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, instanceBuffer );

            int stride = ( 2 + 2 + 1 + 4 + 1 ) * Float.BYTES;
            int offset = 0;

            // Position (vec2)
            gl.glEnableVertexAttribArray( 1 );
            gl.glVertexAttribPointer( 1, 2, GL3.GL_FLOAT, false, stride, offset );
            gl.glVertexAttribDivisor( 1, 1 );
            offset += 2 * Float.BYTES;

            // Scale (vec2)
            gl.glEnableVertexAttribArray( 2 );
            gl.glVertexAttribPointer( 2, 2, GL3.GL_FLOAT, false, stride, offset );
            gl.glVertexAttribDivisor( 2, 1 );
            offset += 2 * Float.BYTES;

            // Rotation (float)
            gl.glEnableVertexAttribArray( 3 );
            gl.glVertexAttribPointer( 3, 1, GL3.GL_FLOAT, false, stride, offset );
            gl.glVertexAttribDivisor( 3, 1 );
            offset += Float.BYTES;

            // Color (vec4)
            gl.glEnableVertexAttribArray( 4 );
            gl.glVertexAttribPointer( 4, 4, GL3.GL_FLOAT, false, stride, offset );
            gl.glVertexAttribDivisor( 4, 1 );
            offset += 4 * Float.BYTES;

            // TextureID (float)
            gl.glEnableVertexAttribArray( 5 );
            gl.glVertexAttribPointer( 5, 1, GL3.GL_FLOAT, false, stride, offset );
            gl.glVertexAttribDivisor( 5, 1 );

            gl.glBindBuffer( GL3.GL_ARRAY_BUFFER, 0 );
            gl.glBindVertexArray( 0 );
        }

        private class ShadersUtils {

            private final ArrayList<Integer> programs = new ArrayList<>( 20 );
            private int vertexShader;
            private int currentProgram;

            // clang-format off
            private final String defaultVertexShader = 
                "#version 330 core\n"+
                "layout (location = 0) in vec2 aPos;    // Quad vertex position\n"+
                "layout (location = 1) in vec2 iPos;    // Instance position\n"+
                "layout (location = 2) in vec2 iScale;  // Instance scale\n"+
                "layout (location = 3) in float iAngle; // Instance rotation\n"+
                "layout (location = 4) in vec4 iColor;  // Instance Color\n"+
                "layout (location = 5) in float iTexture;  // Instance Color\n"+

                "out vec3 TexCoords;\n"+
                "out vec4 ifColor;\n"+

                "void main() {\n"+
                    "// Work directly in NDC space\n"+
                    "float c = cos(iAngle);\n"+
                    "float s = sin(iAngle);\n"+
                    "mat2 rotation = mat2(c, -s, s, c);\n"+
                    
                    "// Scale the vertex position\n"+
                    "vec2 scaled = aPos * iScale;\n"+
                    
                    "// Rotate around origin\n"+
                    "vec2 rotated = rotation * scaled;\n"+
                    
                    "// Add instance position\n"+
                    "vec2 final = rotated + iPos;\n"+
                    
                    "// Pass texture coordinates\n"+
                    "TexCoords = vec3((aPos + 0.5), iTexture);  // Important: Convert from -0.5 to 0.5 to 0.0 to 1.0\n"+
                    
                    "gl_Position = vec4(final, 0.0, 1.0);\n"+
                    // "gl_Position = vec4(aPos, 0.0, 1.0);\n"+
                    "ifColor = iColor;\n"+
                "}";
            private final String defaultFragShader = 
                "#version 330 core\n"+

                "in vec3 TexCoords;\n"+
                "in vec4 ifColor;\n"+

                "out vec4 FragColor;\n"+

                "uniform sampler2DArray myTexture;\n"+

                "void main() {\n"+
                    "if(TexCoords.z > -1){\n"+
                        "FragColor = texture(myTexture, TexCoords);\n"+
                    "} else {\n"+
                        "FragColor = ifColor;\n"+
                    "}\n"+
                "}";
            // clang-format on

            public void use( GL3 gl, int program ) {
                if ( program >= programs.size() ) {
                    return;
                }

                gl.glUseProgram( programs.get( program ) );
                currentProgram = programs.get( program );
            }

            public void setTexture( GL3 gl, int textureChannel ) {
                int texLoc = gl.glGetUniformLocation( currentProgram, "myTexture" );
                gl.glUniform1i( texLoc, textureChannel );
            }

            public void init( GL3 gl ) {
                vertexShader  = createShader( gl, GL3.GL_VERTEX_SHADER, defaultVertexShader );
                int colorFrag = createShader( gl, GL3.GL_FRAGMENT_SHADER, defaultFragShader );
                programs.add( createProgramId( gl, vertexShader, colorFrag ) );
            }

            private int createShader( GL3 gl, int type, String shaderSource ) {
                int shader = gl.glCreateShader( type );
                gl.glShaderSource( shader, 1, new String[] { shaderSource }, null );
                gl.glCompileShader( shader );

                // Check for compilation errors
                IntBuffer compiled = IntBuffer.allocate( 1 );
                gl.glGetShaderiv( shader, GL3.GL_COMPILE_STATUS, compiled );
                if ( compiled.get( 0 ) == GL3.GL_FALSE ) {
                    IntBuffer logLength = Buffers.newDirectIntBuffer( 1 );
                    gl.glGetShaderiv( shader, GL3.GL_INFO_LOG_LENGTH, logLength );

                    // Retrieve error log
                    if ( logLength.get( 0 ) > 0 ) {
                        ByteBuffer logBuffer = Buffers.newDirectByteBuffer( logLength.get( 0 ) );
                        gl.glGetShaderInfoLog( shader, logLength.get( 0 ), null, logBuffer );

                        byte[] logBytes = new byte[logLength.get( 0 )];
                        logBuffer.get( logBytes );
                        System.err.println( "Shader compilation failed: " + new String( logBytes ) );
                    } else {
                        System.err.println( "Shader compilation failed: Unknown error." );
                    }

                    gl.glDeleteShader( shader );
                    return 0;
                }
                return shader;
            }

            private int createProgramId( GL3 gl, int vertexShader, int fragmentShader ) {
                int program = gl.glCreateProgram();
                gl.glAttachShader( program, vertexShader );
                gl.glAttachShader( program, fragmentShader );
                gl.glLinkProgram( program );

                // Check for linking errors
                IntBuffer linked = IntBuffer.allocate( 1 );
                gl.glGetProgramiv( program, GL3.GL_LINK_STATUS, linked );

                if ( linked.get( 0 ) == GL3.GL_FALSE ) {
                    // Get log length
                    int[] logLength = new int[1];
                    gl.glGetProgramiv( program, GL3.GL_INFO_LOG_LENGTH, logLength, 0 );

                    if ( logLength[0] > 0 ) {
                        byte[] logBytes = new byte[logLength[0]];
                        gl.glGetProgramInfoLog( program, logLength[0], logLength, 0, logBytes,
                                                0 );
                        System.err.println( "Program linking failed: " + new String( logBytes ) );
                    } else {
                        System.err.println( "Program linking failed: Unknown error." );
                    }

                    gl.glDeleteProgram( program );
                    return 0;
                }
                return program;
            }
        };

        private class TextureManager {
            public class Pair {
                public int addr;
                public ByteBuffer data;

                public Pair( int addr, ByteBuffer data ) {
                    this.addr = addr;
                    this.data = data;
                }
            };

            private final HashMap<Integer, Integer> textureRegister;
            private final Queue<Pair> bitmapsToLoad;
            private int texturesLoaded      = 0;
            private int glTextureArray      = -1;
            private final int textureWidth  = 64;
            private final int textureHeight = 64;

            private TextureManager() {
                this.texturesLoaded  = 0;
                this.bitmapsToLoad   = new ArrayDeque<>(); // Use a LinkedList or ArrayDeque
                this.textureRegister = new HashMap<>();    // Initialize here!
            }

            public void init( GL3 gl ) {
                IntBuffer text = IntBuffer.allocate( 1 );
                gl.glGenTextures( 1, text );
                glTextureArray = text.get( 0 );
                gl.glBindTexture( GL3.GL_TEXTURE_2D_ARRAY, glTextureArray ); // Bind as texture array

                // Texture array parameters
                gl.glTexParameteri( GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT );
                gl.glTexParameteri( GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_WRAP_T, GL3.GL_REPEAT );
                gl.glTexParameteri( GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST );
                gl.glTexParameteri( GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST );

                gl.glEnable( GL.GL_BLEND );
                gl.glBlendFunc( GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA );

                IntBuffer maxArrayLayers = IntBuffer.allocate( 1 );
                gl.glGetIntegerv( GL3.GL_MAX_ARRAY_TEXTURE_LAYERS, maxArrayLayers );
                System.out.println( "Max texture array layers: " + maxArrayLayers.get( 0 ) );
                int depth = Math.min( GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 6, maxArrayLayers.get( 0 ) );
                gl.glTexStorage3D( GL3.GL_TEXTURE_2D_ARRAY, 1, GL3.GL_RGBA8, textureWidth, textureHeight, depth );

                gl.glBindTexture( GL3.GL_TEXTURE_2D_ARRAY, 0 ); // Bind as texture array
                text.clear();
            }

            public void register( int address ) {

                try {
                    int width       = Memory.getInstance().getHalf( address );
                    int height      = Memory.getInstance().getHalf( address + 2 );
                    int reverseAddr = address + width * height * 4 + 4;

                    ByteBuffer bb = ByteBuffer.allocate( textureWidth * textureHeight * 4 );
                    for ( int i = 0; i < textureWidth * textureHeight; i++ ) {
                        int value = 0;
                        int x     = i % textureWidth;
                        int y     = Math.floorDiv( i, textureWidth );

                        if ( x < width && y >= textureHeight - height ) {
                            if ( x == 0 ) {
                                reverseAddr -= width * 4;
                            }
                            value = Memory.getInstance().getWordNoNotify( reverseAddr );
                            reverseAddr += 4;
                        }

                        bb.putInt( value );
                    }

                    bitmapsToLoad.add( new Pair( address, bb ) );
                } catch ( AddressErrorException ex ) {
                }
            }

            public int push( int addr ) {
                if ( textureRegister.containsKey( addr ) ) {
                    return textureRegister.get( addr );
                }

                register( addr );
                return texturesLoaded + bitmapsToLoad.size() - 1;
            }

            public void populateTexture( GL3 gl ) {
                gl.glBindTexture( GL3.GL_TEXTURE_2D_ARRAY, glTextureArray ); // Bind as texture array
                while ( !bitmapsToLoad.isEmpty() ) {
                    Pair val = bitmapsToLoad.poll();
                    val.data.flip();

                    gl.glTexSubImage3D( GL3.GL_TEXTURE_2D_ARRAY, 0, 0, 0, texturesLoaded, textureWidth, textureHeight, 1, GL3.GL_RGBA, GL3.GL_UNSIGNED_BYTE, val.data );
                    textureRegister.put( val.addr, texturesLoaded++ );
                    val.data.clear();
                }
                gl.glBindTexture( GL3.GL_TEXTURE_2D_ARRAY, 0 ); // Bind as texture array
            }

            public int activateTexture( GL3 gl ) {
                gl.glActiveTexture( GL3.GL_TEXTURE0 ); // Choose your texture unit
                gl.glBindTexture( GL3.GL_TEXTURE_2D_ARRAY, glTextureArray );
                return 0;
            }

            public void dispose( GL3 gl ) {
                if ( glTextureArray == -1 || texturesLoaded == 0 )
                    return;

                IntBuffer tex = IntBuffer.allocate( 1 );
                tex.put( glTextureArray );
                gl.glBindTexture( GL3.GL_TEXTURE_2D_ARRAY, 0 ); // Unbind the texture array
                gl.glDeleteTextures( 1, tex );
                tex.clear();
            }
        }
    }
}
