package mars.tools;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.DebugGL2;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.FPSAnimator;
import com.jogamp.opengl.util.texture.TextureData;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
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

    private final int keyReleaseAddress = 0xffff0010;
    private int keyReleaseAmount        = 0;

    private final int openGLBaseAddress          = 0x10080000;
    private final int openGLColorAddress         = 0x10080000;
    private final int openGLTextureAddress       = openGLColorAddress + GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 2;
    static final int OPENGL_WRITABLE_DATA_AMOUNT = 0x100C0000 - 0x10080000;

    private final ByteBuffer buffer = ByteBuffer.allocate( OPENGL_WRITABLE_DATA_AMOUNT / 2 );

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
        int highAddress = openGLBaseAddress + OPENGL_WRITABLE_DATA_AMOUNT;
        // Special case: baseAddress<0 means we're in kernel memory (0x80000000 and
        // up) and most likely in memory map address space (0xffff0000 and up).  In
        // this case, we need to make sure the high address does not drop off the
        // high end of 32 bit address space.  Highest allowable word address is
        // 0xfffffffc, which is interpreted in Java int as -4.
        addAsObserver( openGLBaseAddress, highAddress );
        addAsObserver( keyPressAddress, keyPressAddress + 0x20 );
    }

    @Override
    protected void processMIPSUpdate( Observable memory,
                                      AccessNotice accessNotice ) {
        if ( accessNotice.getAccessType() == AccessNotice.WRITE ) {
            MemoryAccessNotice mem = (MemoryAccessNotice) accessNotice;

            switch ( mem.getAddress() ) {
                case openGLColorAddress -> {
                    uploadData( mem.getAddress() );
                    canvas.loadColorObjs( buffer );
                    canvas.swapBuffer();
                }
                case openGLTextureAddress -> {
                    uploadData( mem.getAddress() );
                    canvas.loadTextureObjs( buffer );
                    canvas.swapBuffer();
                }

                case keyPressAddress -> {
                    keyPressAmount = 0;
                }

                case keyReleaseAddress -> {
                    keyReleaseAmount = 0;
                }
                default -> {
                }
            }
        }
    }

    private void uploadData( int addr ) {
        buffer.clear();
        try {
            int amount = Memory.getInstance().getWordNoNotify( addr );
            buffer.putInt( amount );
            addr += 4;

            for ( int i = 0; i < amount; i++ ) {
                buffer.putShort( (short) Memory.getInstance().getHalf( addr ) );
                addr += 2;
                buffer.putShort( (short) Memory.getInstance().getHalf( addr ) );
                addr += 2;
                buffer.putShort( (short) Memory.getInstance().getHalf( addr ) );
                addr += 2;
                buffer.putShort( (short) Memory.getInstance().getHalf( addr ) );
                addr += 2;
                buffer.putInt( Memory.getInstance().getWordNoNotify( addr ) );
                addr += 4;
            }
        } catch ( AddressErrorException ex ) {
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
        canvas = new PixelBufferCanvas( width, height, 60 );
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
        panel.addKeyListener( new KeyAdapter() {
            @Override
            public void keyPressed( KeyEvent e ) {
                if ( isObserving() ) {
                    handleKeyEvent( e, KeyType.PRESS );
                }
            }

            @Override
            public void keyReleased( KeyEvent e ) {
                handleKeyEvent( e, KeyType.RELEASE );
            }
        } );

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
        pack();
        setLocationRelativeTo( null );
        panel.setFocusable( true );

        Timer timer = new Timer();
        // timer.scheduleAtFixedRate( new FPSTask( this ), 0, 1000 );

        return panel;
    }

    public class FPSTask extends TimerTask {

        private GameStation parent;

        FPSTask( GameStation parent ) {
            this.parent = parent;
        }

        @Override
        public void run() {
            System.out.println( "fps: " + String.format( "- (%d)", fps ) );
            fps = 0;
        }
    }

    /**
     * Handles a key press by triggering a MIPS interrupt
     */
    private void handleKeyEvent( KeyEvent e, KeyType t ) {
        try {
            if ( t == KeyType.PRESS ) {
                if (keyPressAmount < 8){
                    Globals.memory.setByte( keyPressAddress + 2  + keyPressAmount, e.getKeyCode() );
                }
            } else {
                if (keyReleaseAmount < 8){
                    Globals.memory.setByte( keyReleaseAddress + 2  + keyReleaseAmount, e.getKeyCode() );
                }
            }

            SwingUtilities.invokeLater( () -> {
                SwingUtilities.invokeLater( () -> {
                    logArea.append( String.format( "Key %s: %s (code: %d)\n", t.toString(),
                                                   KeyEvent.getKeyText( e.getKeyCode() ),
                                                   e.getKeyCode() ) );
                    logArea.setCaretPosition( logArea.getDocument().getLength() );
                } );
            } );

        } catch ( AddressErrorException ex ) {
            displayArea.append( "Error accessing memory: " + ex.getMessage() + "\n" );
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
        displayArea.setText( "" );
        isFocused = false;
        updateStatus();

        try {
            // Clear our memory-mapped I/O addresses
            Globals.memory.setWord( keyPressAddress, 0 );
            Globals.memory.setWord( keyReleaseAddress, 0 );

            // Clear all pixels to black
            // canvas.clearPixels( 0x00000000 );
            canvas.display();
        } catch ( AddressErrorException ex ) {
            displayArea.append( "Error resetting memory: " + ex.getMessage() + "\n" );
        }
    }

    protected class PixelBufferCanvas
        extends GLCanvas implements GLEventListener {

        private int width;
        private int height;

        private int[] vertexId      = new int[1];
        private int[] instanceId    = new int[4];
        private int currentInstance = 0;

        private int colorObjAmount             = 0;
        private int textureObjAmount           = 0;
        private final ByteBuffer colorBuffer   = ByteBuffer.allocate( GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 2 );
        private final ByteBuffer textureBuffer = ByteBuffer.allocate( GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 2 );
        private boolean colorNeedUpdate        = false;
        private boolean textureNeedUpdate      = false;

        private volatile boolean initialized = false;
        private volatile boolean contextLost = false;

        private long frameCount = 0;
        private FPSAnimator animator;

        private final ShadersUtils shadersUtil   = new ShadersUtils();
        private final TextureManager textManager = new TextureManager();

        public PixelBufferCanvas( int width, int height, int fps ) {
            super( new GLCapabilities( GLProfile.getDefault() ) );
            this.width       = width;
            this.height      = height;
            this.vertexId[0] = -1;

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

        @Override
        public void init( GLAutoDrawable drawable ) {
            System.out.println( "OpenGL Init called" );

            GL2 gl = drawable.getGL().getGL2();

            try {
                // Enable error checking
                gl = new DebugGL2( gl );
                drawable.setGL( gl );

                createVertexBuffer( gl );
                createInstanceBuffer( gl );
                shadersUtil.init( gl );

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
                System.out.println( "Frame " + frameCount + " - Canvas size: " + getWidth() + "x" + getHeight() );
            }

            if ( !initialized || contextLost ) {
                System.out.println( "Skipping display - Context not ready" );
                return;
            }

            GL2 gl = drawable.getGL().getGL2();
            shadersUtil.use( gl, 0 );

            uploadBuffer( gl );

            try {
                // full area
                gl.glClearColor( 0.2f, 0.2f, 0.2f, 1.0f );
                gl.glClear( GL.GL_COLOR_BUFFER_BIT );

                gl.glBindVertexArray( vertexId[0] );
                drawColors( gl );
                drawTexture( gl );
                gl.glBindVertexArray( 0 );

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

        @Override
        public void reshape( GLAutoDrawable drawable, int x, int y, int width,
                             int height ) {
            System.out.println( "Reshape called: " + width + "x" + height );
            GL2 gl = drawable.getGL().getGL2();

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

            GL2 gl = drawable.getGL().getGL2();
            gl.glDeleteBuffers( 1, vertexId, 0 );
            gl.glDeleteBuffers( 4, instanceId, 0 );
            textManager.dispose( gl );
            initialized = false;
        }

        private void createVertexBuffer( GL2 gl ) {
            if ( vertexId[0] == -1 ) {
                gl.glGenBuffers( 1, vertexId, 0 );
            }

            gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, vertexId[0] );

            // clang-format off
            float[] vertices = {
                -0.5f, -0.5f,  // Bottom left
                -0.5f,  0.5f,  // Top left
                0.5f ,  0.5f,  // Top right
                0.5f , -0.5f   // Bottom right
            };
            // clang-format on

            FloatBuffer vertexBufferData = Buffers.newDirectFloatBuffer( vertices );

            gl.glBufferData( GL2.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, vertexBufferData, GL2.GL_STATIC_DRAW );
            gl.glVertexAttribPointer(0, 2, GL2.GL_FLOAT, false, 2 * Float.BYTES, 0);
            gl.glEnableVertexAttribArray(0);
        }

        private void createInstanceBuffer( GL2 gl ) {
            gl.glGenBuffers( 4, instanceId, 0 );
            for ( int i = 0; i < 4; i++ ) {
                defineAttributes( gl, instanceId[i] );
            }
        }

        public void swapBuffer() {
            this.currentInstance = ( currentInstance + 1 ) % 2;
        }

        public void loadColorObjs( ByteBuffer data ) {
            colorObjAmount = data.getInt();
            for ( int i = 0; i < colorObjAmount; i++ ) {
                uploadObjectColor( data, colorBuffer );
            }
            this.colorNeedUpdate = true;
        }

        public void loadTextureObjs( ByteBuffer data ) {
            textureObjAmount = data.getInt();
            for ( int i = 0; i < textureObjAmount; i++ ) {
                uploadObjectTexture( data, colorBuffer );
            }
            this.textureNeedUpdate = true;
        }

        // Assumes correct GLBuffer is binded
        private void uploadObjectColor( ByteBuffer buffer, ByteBuffer formatedBuffer ) {
            int xy    = buffer.getInt();
            int wh    = buffer.getInt();
            long rotation = buffer.getInt();
            int color = buffer.getInt();

            int x = xy >> 16;
            int y = xy ^ 0xffff;

            int w = wh >> 16;
            int h = wh ^ 0xffff;

            float a = ( ( color >> 24 ) & 0xff ) / 255.0f;
            float r = ( ( color >> 16 ) & 0xff ) / 255.0f;
            float g = ( ( color >> 8 ) & 0xff ) / 255.0f;
            float b = ( color & 0xff ) / 255.0f;

            float fx = (x * 2.0f + w) / width - 1.0f;
            float fy = -(y * 2.0f + h) / height + 1.0f;
            float fw = w * 2 / width;
            float fh = h * 2 / height;
            float rot = (float) (Math.PI * 2.0f * (rotation / 4294967295.0f));

            formatedBuffer.putFloat( fx );
            formatedBuffer.putFloat( fy );
            formatedBuffer.putFloat( fw );
            formatedBuffer.putFloat( fh );
            formatedBuffer.putFloat( rot );
            formatedBuffer.putFloat( r );
            formatedBuffer.putFloat( g );
            formatedBuffer.putFloat( b );
            formatedBuffer.putFloat( a );
            formatedBuffer.putInt( 0 );
        }

        private void uploadObjectTexture( ByteBuffer buffer, ByteBuffer formatedBuffer ) {
            int xy = buffer.getInt();
            int wh = buffer.getInt();
            long rotation = buffer.getInt();

            int x = xy >> 16;
            int y = xy ^ 0xffff;
            int w = wh >> 16;
            int h = wh ^ 0xffff;

            float fx = (x * 2.0f + w) / width - 1.0f;
            float fy = -(y * 2.0f + h) / height + 1.0f;
            float fw = w * 2 / width;
            float fh = h * 2 / height;
            float rot = (float) (Math.PI * 2.0f * (rotation / 4294967295.0f));


            int textAddr = buffer.getInt();

            formatedBuffer.putFloat( fx );
            formatedBuffer.putFloat( fy );
            formatedBuffer.putFloat( fw );
            formatedBuffer.putFloat( fh );
            formatedBuffer.putFloat( rot );
            formatedBuffer.putFloat( 0 );
            formatedBuffer.putFloat( 0 );
            formatedBuffer.putFloat( 0 );
            formatedBuffer.putFloat( 0 );
            formatedBuffer.putInt( textManager.push( textAddr ) );
        }

        private void defineAttributes( GL2 gl, int instanceBuffer ) {
            gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, instanceBuffer );

            int stride = ( 2 + 2 + 1 + 4 ) * Float.BYTES + Integer.BYTES; // vec2f + vec2f + float + vec4f + int
            int offset = 0;

            // Layout A: position (vec2)
            gl.glVertexAttribPointer( 1, 2, GL2.GL_FLOAT, false, stride, offset );
            gl.glEnableVertexAttribArray( 1 );
            gl.glVertexAttribDivisor( 1, 1 );

            offset += 2 * Float.BYTES;

            // Layout A: position (vec2)
            gl.glVertexAttribPointer( 2, 2, GL2.GL_FLOAT, false, stride, offset );
            gl.glEnableVertexAttribArray( 2 );
            gl.glVertexAttribDivisor( 2, 1 );

            offset += 2 * Float.BYTES;

            // Layout A: rotation (float)
            gl.glVertexAttribPointer( 3, 2, GL2.GL_FLOAT, false, stride, offset );
            gl.glEnableVertexAttribArray( 3 );
            gl.glVertexAttribDivisor( 3, 1 );

            offset += 1 * Float.BYTES;

            // Layout A: color (vec4)
            gl.glVertexAttribPointer( 4, 4, GL2.GL_FLOAT, false, stride, offset );
            gl.glEnableVertexAttribArray( 4 );
            gl.glVertexAttribDivisor( 4, 1 );

            offset += 4 * Float.BYTES;

            // Layout A: textureIndex (int)
            gl.glVertexAttribIPointer( 5, 1, GL2.GL_INT, stride, offset );
            gl.glEnableVertexAttribArray( 5 );
            gl.glVertexAttribDivisor( 5, 1 );
        }

        private void drawColors( GL2 gl ) {
            int colorVBO = instanceId[currentInstance * 2];
            gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, colorVBO );
            gl.glDrawArraysInstanced( GL2.GL_TRIANGLE_FAN, 0, 4, colorObjAmount );
        }

        private void drawTexture( GL2 gl ) {
            int textVBO = instanceId[currentInstance * 2 + 1];

            shadersUtil.setTexture( gl, textManager.activateTexture( gl ) );
            gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, textVBO );
            gl.glDrawArraysInstanced( GL2.GL_TRIANGLE_FAN, 0, 4, textureObjAmount );
        }

        private void uploadBuffer( GL2 gl ) {
            int i        = ( currentInstance + 1 ) % 2;
            int colorVBO = instanceId[i * 2];
            int textVBO  = instanceId[i * 2 + 1];

            if ( this.colorNeedUpdate ) {
                gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, colorVBO );
                gl.glBufferData( GL2.GL_ARRAY_BUFFER, colorBuffer.limit(), colorBuffer, GL2.GL_DYNAMIC_DRAW );
            }

            if ( this.textureNeedUpdate ) {
                gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, textVBO );
                gl.glBufferData( GL2.GL_ARRAY_BUFFER, textureBuffer.limit(), textureBuffer, GL2.GL_DYNAMIC_DRAW );
                textManager.populateTexture( gl );
            }
        }

        private class ShadersUtils {

            private ArrayList<Integer> programs;
            private int vertexShader;
            private int currentProgram;

            // clang-format off
            private final String defaultVertexShader = """
                #version 330 core
                layout (location = 0) in vec2 aPos;    // Quad vertex position
                layout (location = 1) in vec2 iPos;    // Instance position
                layout (location = 2) in vec2 iScale;  // Instance scale
                layout (location = 3) in float iAngle; // Instance rotation
                layout (location = 4) in vec4 iColor;  // Instance Color
                layout (location = 5) in int iTexture;  // Instance Color

                out vec3 TexCoords;
                out vec4 ifColor;

                void main() {
                    // Work directly in NDC space
                    float c = cos(iAngle);
                    float s = sin(iAngle);
                    mat2 rotation = mat2(c, -s, s, c);
                    
                    // Scale the vertex position
                    vec2 scaled = aPos * iScale;
                    
                    // Rotate around origin
                    vec2 rotated = rotation * scaled;
                    
                    // Add instance position
                    vec2 final = rotated + iPos;
                    
                    // Pass texture coordinates
                    TexCoords = vec3((aPos + 0.5), iTexture);  // Important: Convert from -0.5 to 0.5 to 0.0 to 1.0
                    
                    gl_Position = vec4(final, 0.0, 1.0);
                    ifColor = iColor;
                } 
            """;
            private final String defaultFragShader = """
                #version 330 core

                in vec3 TexCoords;
                in vec4 ifColor;
                out vec4 FragColor;

                uniform sampler2D myTexture;
                uniform bool useTexture;

                void main() {
                    if(useTexture){
                        FragColor = texture(myTexture, TexCoords);
                    } else {
                        FragColor = ifColor;
                    }
                }
            """; // clang-format on



            public void use( GL2 gl, int program ) {
                if ( program >= programs.size() ) {
                    return;
                }

                gl.glUseProgram( programs.get( program ) );
                currentProgram = programs.get( program );
            }

            public void enableTextureMode(GL2 gl){
                gl.glUniform1i(gl.glGetUniformLocation(currentProgram, "useTexture"), 1);
            }

            public void setTexture(GL2 gl, int textureChannel){
                int texLoc = gl.glGetUniformLocation(currentProgram, "myTexture");
                gl.glUniform1i(texLoc, textureChannel);
            }

            public void disableTextureMode(GL2 gl){
                gl.glUniform1i(gl.glGetUniformLocation(currentProgram, "useTexture"), 0);
            }

            public void init( GL2 gl ) {
                vertexShader  = createShader( gl, GL2.GL_VERTEX_SHADER, defaultVertexShader );
                int colorFrag = createShader( gl, GL2.GL_FRAGMENT_SHADER, defaultFragShader );
                programs.add( createProgramId( gl, vertexShader, colorFrag ) );
            }

            private int createShader( GL2 gl, int type, String shaderSource ) {
                int shader = gl.glCreateShader( type );
                gl.glShaderSource( shader, 1, new String[] { shaderSource }, null );
                gl.glCompileShader( shader );

                // Check for compilation errors
                IntBuffer compiled = IntBuffer.allocate( 1 );
                gl.glGetShaderiv( shader, GL2.GL_COMPILE_STATUS, compiled );
                if ( compiled.get( 0 ) == GL2.GL_FALSE ) {
                    IntBuffer logLength = Buffers.newDirectIntBuffer( 1 );
                    gl.glGetShaderiv( shader, GL2.GL_INFO_LOG_LENGTH, logLength );

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

            private int createProgramId( GL2 gl, int vertexShader, int fragmentShader ) {
                int program = gl.glCreateProgram();
                gl.glAttachShader( program, vertexShader );
                gl.glAttachShader( program, fragmentShader );
                gl.glLinkProgram( program );

                // Check for linking errors
                IntBuffer linked = IntBuffer.allocate( 1 );
                gl.glGetProgramiv( program, GL2.GL_LINK_STATUS, linked );

                if ( linked.get( 0 ) == GL2.GL_FALSE ) {
                    // Get log length
                    int[] logLength = new int[1];
                    gl.glGetProgramiv( program, GL2.GL_INFO_LOG_LENGTH, logLength, 0 );

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
            public class Pair{
                public int addr;
                public ByteBuffer data;

                public Pair(int addr, ByteBuffer data) {
                    this.addr = addr;
                    this.data = data;
                }
            };

            private final HashMap<Integer, Integer> textureRegister;
            private final Queue<Pair> bitmapsToLoad;
            private int texturesLoaded = 0;
            private int glTextureArray = -1;

            private TextureManager() {
                this.texturesLoaded = 0;
                this.bitmapsToLoad = new ArrayDeque<>(); // Use a LinkedList or ArrayDeque
                this.textureRegister = new HashMap<>(); // Initialize here!
            }

            public void init(GL2 gl){
                IntBuffer text = IntBuffer.allocate(1);
                gl.glGenTextures(1, text);
                glTextureArray = text.get(0);
                gl.glBindTexture(GL2.GL_TEXTURE_2D_ARRAY, glTextureArray);  // Bind as texture array

                // Texture array parameters
                gl.glTexParameteri(GL2.GL_TEXTURE_2D_ARRAY, GL2.GL_TEXTURE_WRAP_S, GL2.GL_REPEAT);
                gl.glTexParameteri(GL2.GL_TEXTURE_2D_ARRAY, GL2.GL_TEXTURE_WRAP_T, GL2.GL_REPEAT);
                gl.glTexParameteri(GL2.GL_TEXTURE_2D_ARRAY, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
                gl.glTexParameteri(GL2.GL_TEXTURE_2D_ARRAY, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);

                gl.glTexStorage3D(GL2.GL_TEXTURE_2D_ARRAY, 1, GL2.GL_RGBA8, 64, 64, (OPENGL_WRITABLE_DATA_AMOUNT / 6)); 

                text.clear();
            }

            public void register( int address ) {

                try {
                    int width  = Memory.getInstance().getHalf( address );
                    int height = Memory.getInstance().getHalf( address + 2 );

                    ByteBuffer bb = ByteBuffer.allocate( width * height + 4 );
                    for ( int i = 0; i < width * height; i++ ) {
                        int addr = address + 4 + i * 4;
                        bb.putInt( Memory.getInstance().getWordNoNotify( addr ) );
                    }

                    bitmapsToLoad.add(  new Pair(address, bb));
                } catch ( AddressErrorException ex ) {
                }
            }

            public int push( int addr ) {
                if ( textureRegister.containsKey( addr ) ) {
                    return textureRegister.get(addr);
                }

                register( addr );
                return texturesLoaded + bitmapsToLoad.size() - 1;
            }

            public void populateTexture( GL2 gl ) {
                while(! bitmapsToLoad.isEmpty()){
                    Pair val    = bitmapsToLoad.poll();

                    gl.glTexSubImage3D(GL2.GL_TEXTURE_2D_ARRAY, 0, 0, 0, texturesLoaded, 64, 64, 1, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, val.data);
                    textureRegister.put(val.addr, texturesLoaded++);
                    val.data.clear();
                }
            }

            public int activateTexture(GL2 gl){
                gl.glActiveTexture(GL2.GL_TEXTURE0); // Choose your texture unit
                gl.glBindTexture(GL2.GL_TEXTURE_2D_ARRAY, glTextureArray);
                return 0;
            }

            public void dispose(GL2 gl){
                IntBuffer tex = IntBuffer.allocate(1);
                tex.put(glTextureArray);
                gl.glBindTexture(GL2.GL_TEXTURE_2D_ARRAY, 0); // Unbind the texture array
                gl.glDeleteTextures(1, tex);
                tex.clear();
            }
        }
    }
}
