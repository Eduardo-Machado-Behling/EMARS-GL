
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
import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
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

    private final int keyPressAddress            = 0xffff0000;
    private final int keyReleaseAddress          = 0xffff0010;
    private final int openGLBaseAddress          = 0x10080000;
    private final int openGLColorAddress         = 0x10080000;
    private final int openGLTextureAddress       = 0x10080000 + GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 2;
    static final int OPENGL_WRITABLE_DATA_AMOUNT = 0x100C0000 - 0x10080000;

    enum KeyType { PRESS,
                   RELEASE }

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
        JScrollPane scrollPanel =
            new JScrollPane( logArea ); // scrollPanel must contain logArea
        secondRowPanel.add( scrollPanel );

        // Create status label to show focus state
        JPanel thirdRowPanel =
            new JPanel( new FlowLayout( FlowLayout.LEFT ) ); // Or any other layout
        statusLabel = new JLabel( "Click here and connect to enable keyboard" );
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
                Globals.memory.setWord( keyPressAddress, e.getKeyCode() );
            } else {
                Globals.memory.setWord( keyReleaseAddress, e.getKeyCode() );
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

    public void setPixel( int x, int y, int color ) {
        // canvas.updatePixel( x, y, color );
    }

    protected class PixelBufferCanvas
        extends GLCanvas implements GLEventListener {
        private int width;
        private int height;

        private int[] vertexId      = new int[1];
        private int[] instanceId    = new int[2];
        private int currentInstance = 0;
        private int[] textureId     = new int[1];

        private int colorObjAmount       = 0;
        private int textureObjAmount     = 0;
        private ByteBuffer colorBuffer   = ByteBuffer.allocate( GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 2 );
        private ByteBuffer textureBuffer = ByteBuffer.allocate( GameStationOpenGL.OPENGL_WRITABLE_DATA_AMOUNT / 2 );
        private boolean needsRedraw      = false;

        private volatile boolean initialized = false;
        private volatile boolean contextLost = false;

        private long frameCount = 0;
        private FPSAnimator animator;

        private ShadersUtils shadersUtil;

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
                    System.out.println( "Canvas resized to: " + getWidth() + "x" +
                                        getHeight() );
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
                System.out.println( "Frame " + frameCount +
                                    " - Canvas size: " + getWidth() + "x" + getHeight() );
            }

            if ( !initialized || contextLost ) {
                System.out.println( "Skipping display - Context not ready" );
                return;
            }

            GL2 gl = drawable.getGL().getGL2();

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
                    System.err.println( "OpenGL error in display: 0x" +
                                        Integer.toHexString( error ) );
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
            if ( !initialized )
                return;

            GL2 gl = drawable.getGL().getGL2();
            gl.glDeleteBuffers( 1, vertexId, 0 );
            gl.glDeleteTextures( 1, textureId, 0 );
            gl.glDeleteBuffers( 2, instanceId, 0 );
            initialized = false;
        }

        private void createVertexBuffer( GL2 gl ) {
            if ( vertexId[0] == -1 )
                gl.glGenBuffers( 1, vertexId, 0 );

            gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, vertexId[0] );
            float ndcX = 2.0f / width - 1;
            float ndcY = 2.0f / height - 1;

            float[] vertices = {
                -1.0f,
                -1.0f,
                ndcX,
                -1.0f,
                -1.0f,
                ndcY,
                ndcX,
                ndcY,
            };
            FloatBuffer vertexBufferData = Buffers.newDirectFloatBuffer( vertices );
            gl.glBufferData( GL2.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, vertexBufferData, GL2.GL_STATIC_DRAW );
        }

        private void createInstanceBuffer( GL2 gl ) {
            gl.glGenBuffers( 2, instanceId, 0 );
            for ( int i = 0; i < 2; i++ ) {
                defineAttributes( gl, instanceId[i] );
            }
        }

        public void loadColorObjs( ByteBuffer data ) {
            colorObjAmount = data.getInt();
            for ( int i = 0; i < colorObjAmount; i++ ) {
                uploadObjectColor( data, colorBuffer );
            }
            this.needsRedraw = true;
        }

        public void loadTextureObjs( ByteBuffer data ) {
            textureObjAmount = data.getInt();
            for ( int i = 0; i < textureObjAmount; i++ ) {
                uploadObjectTexture( data, colorBuffer );
            }
            this.needsRedraw = true;
        }

        // Assumes correct GLBuffer is binded
        private void uploadObjectColor( ByteBuffer buffer, ByteBuffer formatedBuffer ) {
            int xy    = buffer.getInt();
            int wh    = buffer.getInt();
            int color = buffer.getInt();

            int x = xy >> 16;
            int y = xy ^ 0xffff;

            int w = wh >> 16;
            int h = wh ^ 0xffff;

            float a = ( ( color >> 24 ) & 0xff ) / 256.0f;
            float r = ( ( color >> 16 ) & 0xff ) / 256.0f;
            float g = ( ( color >> 8 ) & 0xff ) / 256.0f;
            float b = ( color & 0xff ) / 256.0f;

            formatedBuffer.putInt( x );
            formatedBuffer.putInt( y );
            formatedBuffer.putInt( h );
            formatedBuffer.putInt( w );
            formatedBuffer.putFloat( r );
            formatedBuffer.putFloat( g );
            formatedBuffer.putFloat( b );
            formatedBuffer.putFloat( a );
        }

        private void uploadObjectTexture( ByteBuffer buffer, ByteBuffer formatedBuffer ) {
            int xy = buffer.getInt();
            int wh = buffer.getInt();

            int x = xy >> 16;
            int y = xy ^ 0xffff;

            int w = wh >> 16;
            int h = wh ^ 0xffff;

            formatedBuffer.putInt( x );
            formatedBuffer.putInt( y );
            formatedBuffer.putInt( h );
            formatedBuffer.putInt( w );
        }

        private void defineAttributes( GL2 gl, int instanceBuffer ) {
            gl.glBindBuffer( GL2.GL_ARRAY_BUFFER, instanceBuffer );

            int strideA = ( 2 + 2 + 4 ) * Integer.BYTES; // position (2 int) + color (4 float)

            // Layout A: position (vec2i)
            gl.glEnableVertexAttribArray( 0 );
            gl.glVertexAttribIPointer( 0, 2, GL2.GL_INT, strideA, 0 );
            gl.glVertexAttribDivisor( 0, 1 );

            // Layout A: position (vec2i)
            gl.glEnableVertexAttribArray( 0 );
            gl.glVertexAttribIPointer( 1, 2, GL2.GL_INT, strideA, 0 );
            gl.glVertexAttribDivisor( 1, 1 );

            // Layout A: color (vec4f)
            gl.glEnableVertexAttribArray( 1 );
            gl.glVertexAttribPointer( 2, 4, GL2.GL_FLOAT, false, strideA, 2 * Integer.BYTES );
            gl.glVertexAttribDivisor( 2, 1 );
        }

        private void drawColors( GL2 gl ) {
            shadersUtil.use( gl, 0 );
            gl.glDrawArraysInstanced( GL2.GL_TRIANGLE_FAN, 0, 4, colorObjAmount );
        }

        private void drawTexture( GL2 gl ) {
            shadersUtil.use( gl, 1 );
            gl.glDrawArraysInstanced( GL2.GL_TRIANGLE_FAN, 0, 4, textureObjAmount );
        }

        private class ShadersUtils {
            private ArrayList<Integer> programs;
            private int vertexShader;

            private final String defaultVertexShader = "#version 330 core\n"
                                                       +
                                                       "layout (location = 0) in vec2 aPos;\n"
                                                       +
                                                       "layout (location = 1) in vec2 aTexCoord;\n"
                                                       +

                                                       "// Instance Attributes\n"
                                                       +
                                                       "layout( location = 2 ) in vec2 instancePos;\n"
                                                       +
                                                       "layout( location = 3 ) in vec2 instanceScale;\n"
                                                       +
                                                       "layout( location = 4 ) in vec4 instanceColor;\n"
                                                       +

                                                       "out vec2 TexCoord;\n"
                                                       +
                                                       "out vec4 FragColor;\n"
                                                       +

                                                       "void main() {\n"
                                                       +
                                                       "// Scale and position the quad\n"
                                                       +
                                                       "vec2 scaledPos = aPos * instanceScale + instancePos;\n"
                                                       +

                                                       "gl_Position = vec4( scaledPos / vec2( 512.0, 256.0 ) * 2.0 - 1.0, 0.0, 1.0 );\n"
                                                       +
                                                       "TexCoord    = aTexCoord;\n"
                                                       +
                                                       "FragColor   = instanceColor;\n"
                                                       +
                                                       "}\n";
            private final String defaultColorFrag =
                "#version 330 core\n"
                +
                "in vec2 TexCoord;\n"
                +
                "in vec4 FragColor;\n"
                +

                "out vec4 FragColorOut;\n"
                +

                "uniform sampler2D textureSampler;\n"
                +

                "void main() {\n"
                +
                "FragColorOut = FragColor; // Modulate texture with instance color\n"
                +
                "}\n";

            private final String defaultTextureFrag =
                "";

            public void init( GL2 gl ) {
                vertexShader  = createShader( gl, GL2.GL_VERTEX_SHADER, defaultVertexShader );
                int colorFrag = createShader( gl, GL2.GL_FRAGMENT_SHADER, defaultColorFrag );
                programs.add( createProgramId( gl, vertexShader, colorFrag ) );

                // int textureFrag = createShader( gl, GL2.GL_FRAGMENT_SHADER, defaultTextureFrag );
                // programs.add( createProgramId( gl, vertexShader, textureFrag ) );
            }

            public int use( GL2 gl, int program ) {
                if ( program >= programs.size() )
                    return 0;

                gl.glUseProgram( programs.get( program ) );
                return programs.get( program );
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
                        System.err.println( "Shader compilation failed: " +
                                            new String( logBytes ) );
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
    }
}