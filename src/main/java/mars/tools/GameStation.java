
package mars.tools;

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
import java.nio.ByteOrder;
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
import javax.swing.border.Border;
import mars.Globals; // Ensure this class exists in your project
import mars.mips.hardware.AccessNotice; // Ensure this class exists in your project
import mars.mips.hardware.AddressErrorException; // Ensure this class exists in your project
import mars.mips.hardware.Memory; // Ensure this class exists in your project
import mars.mips.hardware.MemoryAccessNotice; // Ensure this class exists in your project

public class GameStation extends AbstractMarsToolAndApplication {
  // GUI components
  private JTextArea displayArea;
  private JLabel statusLabel;
  private boolean isFocused;

  private int width = 512;
  private int height = 256;
  private PixelBufferCanvas canvas;
  private int fps = 0;

  private JTextArea logArea; // New log area

  private int baseAddress = 0x10080000;
  private int keyPressAddress;
  private int keyReleaseAddress;
  private int displayRedrawAddress;
  private int displayBufferAddress;

  enum KeyType { PRESS, RELEASE }

  /**
   * Constructor sets up the tool's basic properties
   */
  public GameStation() {
    super("GameStation", "Simulate console");
    isFocused = false;
    updateAddresses(baseAddress);
  }

  /**
   * Returns the name that will appear in MARS Tools menu
   */
  public String getName() { return "Game Station"; }

  private void updateAddresses(int newBase) {
    baseAddress = newBase;
    keyPressAddress = newBase;
    newBase += Memory.WORD_LENGTH_BYTES;
    keyReleaseAddress = newBase;
    newBase += Memory.WORD_LENGTH_BYTES;
    displayRedrawAddress = newBase;
    newBase += Memory.WORD_LENGTH_BYTES;
    displayBufferAddress = newBase;
  }

  /**
   * Set up our tool to observe memory
   */
  protected void addAsObserver() {
    int highAddress =
        baseAddress + (3 + width * height) * Memory.WORD_LENGTH_BYTES;
    // Special case: baseAddress<0 means we're in kernel memory (0x80000000 and
    // up) and most likely in memory map address space (0xffff0000 and up).  In
    // this case, we need to make sure the high address does not drop off the
    // high end of 32 bit address space.  Highest allowable word address is
    // 0xfffffffc, which is interpreted in Java int as -4.
    addAsObserver(baseAddress, highAddress);
  }

  protected void processMIPSUpdate(Observable memory,
                                   AccessNotice accessNotice) {
    if (accessNotice.getAccessType() == AccessNotice.READ) {
      MemoryAccessNotice mem = (MemoryAccessNotice)accessNotice;

      try {
        if (mem.getAddress() == keyPressAddress && mem.getValue() != 0) {
          Globals.memory.setWord(keyPressAddress, 0);
        } else if (mem.getAddress() == keyReleaseAddress &&
                   mem.getValue() != 0) {
          Globals.memory.setWord(keyReleaseAddress, 0);
        }
      } catch (AddressErrorException ex) {
      }
    } else if (accessNotice.getAccessType() == AccessNotice.WRITE) {
      MemoryAccessNotice mem = (MemoryAccessNotice)accessNotice;

      if (mem.getAddress() == displayRedrawAddress) {
        canvas.swapBuffer();
        fps += 1;
      } else if (mem.getAddress() >= displayBufferAddress &&
                 (mem.getAddress() - displayBufferAddress) <=
                     height * width * Memory.WORD_LENGTH_BYTES) {
        int address = mem.getAddress() - displayBufferAddress;
        int adr = address / Memory.WORD_LENGTH_BYTES;
        int x = (adr) % (width);
        int y = Math.floorDiv(adr, width);
        int p = mem.getValue();

        setPixel(x, y, p);
      }
    }
  }

  @Override
  protected void updateDisplay() {}

  /**
   * Builds the main interface for the tool
   */
  protected JComponent buildMainDisplayArea() {
    System.out.println("Build");
    JPanel panel = new JPanel();
    panel.setLayout(
        new BoxLayout(panel, BoxLayout.Y_AXIS)); // Vertical BoxLayout

    // OpenGL Canvas
    JPanel firstRowPanel = new JPanel(new BorderLayout());
    firstRowPanel.setBackground(Color.CYAN);
    canvas = new PixelBufferCanvas(width, height, 60);
    firstRowPanel.add(canvas, BorderLayout.CENTER);
    firstRowPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    JPanel secondRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    logArea = new JTextArea(5, 40);
    logArea.setEditable(false);
    logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    JScrollPane scrollPanel =
        new JScrollPane(logArea); // scrollPanel must contain logArea
    secondRowPanel.add(scrollPanel);

    // Create status label to show focus state
    JPanel thirdRowPanel =
        new JPanel(new FlowLayout(FlowLayout.LEFT)); // Or any other layout
    statusLabel = new JLabel("Click here and connect to enable keyboard");
    statusLabel.setHorizontalAlignment(JLabel.CENTER);
    thirdRowPanel.add(statusLabel);

    // Add key listener to the panel
    panel.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (isObserving()) {
          handleKeyEvent(e, KeyType.PRESS);
        }
      }

      @Override
      public void keyReleased(KeyEvent e) {
        handleKeyEvent(e, KeyType.RELEASE);
      }
    });

    // Add mouse listener to handle focus
    panel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        panel.requestFocusInWindow();
        isFocused = true;
        updateStatus();
      }
    });

    // Add focus listeners
    panel.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        isFocused = true;
        updateStatus();
      }

      @Override
      public void focusLost(FocusEvent e) {
        isFocused = false;
        updateStatus();
      }
    });

    panel.add(firstRowPanel);
    panel.add(secondRowPanel);
    panel.add(thirdRowPanel);

    setContentPane(panel);
    pack();
    setLocationRelativeTo(null);
    panel.setFocusable(true);

    Timer timer = new Timer();
    timer.scheduleAtFixedRate(new FPSTask(this), 0, 1000);

    return panel;
  }

  public class FPSTask extends TimerTask {
    private GameStation parent;

    FPSTask(GameStation parent) { this.parent = parent; }

    @Override
    public void run() {
      System.out.println("fps: " + String.format("- (%d)", fps));
      fps = 0;
    }
  }

  /**
   * Handles a key press by triggering a MIPS interrupt
   */
  private void handleKeyEvent(KeyEvent e, KeyType t) {
    try {
      if (t == KeyType.PRESS) {
        Globals.memory.setWord(keyPressAddress, e.getKeyCode());
      } else {
        Globals.memory.setWord(keyReleaseAddress, e.getKeyCode());
      }

      SwingUtilities.invokeLater(() -> {
        SwingUtilities.invokeLater(() -> {
          logArea.append(String.format("Key %s: %s (code: %d)\n", t.toString(),
                                       KeyEvent.getKeyText(e.getKeyCode()),
                                       e.getKeyCode()));
          logArea.setCaretPosition(logArea.getDocument().getLength());
        });
      });

    } catch (AddressErrorException ex) {
      displayArea.append("Error accessing memory: " + ex.getMessage() + "\n");
    }
  }

  /**
   * Updates the status label based on focus and connection state
   */
  private void updateStatus() {
    if (!isFocused) {
      statusLabel.setText("Click here to enable keyboard input");
    } else if (!isObserving()) {
      statusLabel.setText("Click 'Connect' to enable interrupts");
    } else {
      statusLabel.setText("Ready for keyboard input");
    }
  }

  /**
   * Reset clears the display and memory
   */
  protected void reset() {
    displayArea.setText("");
    isFocused = false;
    updateStatus();

    try {
      // Clear our memory-mapped I/O addresses
      Globals.memory.setWord(keyPressAddress, 0);
      Globals.memory.setWord(keyReleaseAddress, 0);

      // Clear all pixels to black
      canvas.clearPixels(0x00000000);
      canvas.display();
    } catch (AddressErrorException ex) {
      displayArea.append("Error resetting memory: " + ex.getMessage() + "\n");
    }
  }

  public void setPixel(int x, int y, int color) {
    canvas.updatePixel(x, y, color);
  }

  protected class PixelBufferCanvas
      extends GLCanvas implements GLEventListener {
    private static final int BORDER_SIZE =
        2; // Add a visible border so we can see the canvas bounds
    private final int width;
    private final int height;
    private int pboId = -1;
    private int textureId = -1;
    private ByteBuffer[] buffer = new ByteBuffer[2];
    private int currentBuffer = 0;
    private volatile boolean initialized = false;
    private volatile boolean contextLost = false;
    private long frameCount = 0;
    private FPSAnimator animator;

    public PixelBufferCanvas(int width, int height, int fps) {
      super(new GLCapabilities(GLProfile.getDefault()));
      this.width = width;
      this.height = height;

      if (fps != 0) {
        this.animator = new FPSAnimator(this, fps);
        animator.start();
      }

      // Force the canvas size to be fixed
      Dimension size =
          new Dimension(width + 2 * BORDER_SIZE, height + 2 * BORDER_SIZE);
      setPreferredSize(size);
      setMinimumSize(size);
      setMaximumSize(size);

      // Add our GLEventListener
      addGLEventListener(this);

      // Add a component listener to detect visibility changes
      addComponentListener(new ComponentAdapter() {
        @Override
        public void componentShown(ComponentEvent e) {
          System.out.println("Canvas shown");
        }

        @Override
        public void componentHidden(ComponentEvent e) {
          System.out.println("Canvas hidden");
        }

        @Override
        public void componentResized(ComponentEvent e) {
          System.out.println("Canvas resized to: " + getWidth() + "x" +
                             getHeight());
        }
      });
    }

    @Override
    public void init(GLAutoDrawable drawable) {
      System.out.println("OpenGL Init called");

      GL2 gl = drawable.getGL().getGL2();

      try {
        // Enable error checking
        gl = new DebugGL2(gl);
        drawable.setGL(gl);

        // Create and setup texture
        int[] ids = new int[1];
        gl.glGenTextures(1, ids, 0);
        textureId = ids[0];

        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER,
                           GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER,
                           GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S,
                           GL2.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T,
                           GL2.GL_CLAMP_TO_EDGE);

        // Initialize texture storage
        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGB8, width, height, 0,
                        GL2.GL_RGB, GL2.GL_UNSIGNED_BYTE, null);

        // Create PBO
        gl.glGenBuffers(1, ids, 0);
        pboId = ids[0];

        // Initialize PBO
        gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER, pboId);
        gl.glBufferData(GL2.GL_PIXEL_UNPACK_BUFFER, width * height * 3, null,
                        GL2.GL_STREAM_DRAW);
        gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER, 0);

        // Create CPU-side buffer
        for (int i = 0; i < buffer.length; i++) {
          buffer[i] = ByteBuffer.allocateDirect(width * height * 3);
          buffer[i].order(ByteOrder.nativeOrder());
        }

        // Clear to a bright color so we can see if rendering is working
        clearPixels(0xFF00FF); // Bright magenta

        initialized = true;
        contextLost = false;
        System.out.println("OpenGL initialization successful");

      } catch (GLException e) {
        System.err.println("OpenGL initialization failed: " + e.getMessage());
        e.printStackTrace();
        contextLost = true;
      }
    }

    @Override
    public void display(GLAutoDrawable drawable) {
      frameCount++;
      if (frameCount % 60 == 0) { // Log every 60 frames
        System.out.println("Frame " + frameCount +
                           " - Canvas size: " + getWidth() + "x" + getHeight());
      }

      if (!initialized || contextLost) {
        System.out.println("Skipping display - Context not ready");
        return;
      }

      GL2 gl = drawable.getGL().getGL2();

      try {
        // Clear to dark gray so we can see if the texture isn't covering the
        // full area
        gl.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT);

        // Update texture from PBO
        gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER, pboId);
        gl.glBufferSubData(GL2.GL_PIXEL_UNPACK_BUFFER, 0, width * height * 3,
                           buffer[currentBuffer]);

        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glTexSubImage2D(GL2.GL_TEXTURE_2D, 0, 0, 0, width, height,
                           GL2.GL_RGB, GL2.GL_UNSIGNED_BYTE, 0);

        // Draw texture
        gl.glEnable(GL2.GL_TEXTURE_2D);
        gl.glBegin(GL2.GL_QUADS);
        gl.glTexCoord2f(0, 1);
        gl.glVertex2f(-1, -1);
        gl.glTexCoord2f(1, 1);
        gl.glVertex2f(1, -1);
        gl.glTexCoord2f(1, 0);
        gl.glVertex2f(1, 1);
        gl.glTexCoord2f(0, 0);
        gl.glVertex2f(-1, 1);
        gl.glEnd();
        gl.glDisable(GL2.GL_TEXTURE_2D);

        // Clean up bindings
        gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER, 0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);

        int error = gl.glGetError();
        if (error != GL.GL_NO_ERROR) {
          System.err.println("OpenGL error in display: 0x" +
                             Integer.toHexString(error));
        }

      } catch (GLException e) {
        System.err.println("Display error: " + e.getMessage());
        e.printStackTrace();
        contextLost = true;
      }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width,
                        int height) {
      System.out.println("Reshape called: " + width + "x" + height);
      GL2 gl = drawable.getGL().getGL2();
      gl.glViewport(0, 0, width, height);
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
      System.out.println("Dispose called");
      if (!initialized)
        return;

      GL2 gl = drawable.getGL().getGL2();
      gl.glDeleteBuffers(1, new int[] {pboId}, 0);
      gl.glDeleteTextures(1, new int[] {textureId}, 0);
      initialized = false;
    }

    public void swapBuffer() {
      int oldBuffer = currentBuffer;
      currentBuffer = (currentBuffer + 1) % buffer.length;

      buffer[oldBuffer].rewind();
      buffer[currentBuffer].rewind();
      buffer[oldBuffer].put(buffer[currentBuffer]);
      buffer[oldBuffer].flip();
      buffer[currentBuffer].rewind();
    }

    public void updatePixel(int x, int y, int color) {
      if (!initialized || x < 0 || x >= width || y < 0 || y >= height)
        return;

      int index = (y * width + x) * 3;
      int nextBuffer = (currentBuffer + 1) % buffer.length;
      buffer[nextBuffer].put(index, (byte)((color >> 16) & 0xff));    // Red
      buffer[nextBuffer].put(index + 1, (byte)((color >> 8) & 0xff)); // Green
      buffer[nextBuffer].put(index + 2, (byte)(color & 0xff));        // Blue
    }

    public void clearPixels(int color) {
      if (!initialized)
        return;

      byte r = (byte)((color >> 16) & 0xff);
      byte g = (byte)((color >> 8) & 0xff);
      byte b = (byte)(color & 0xff);

      for (int i = 0; i < buffer.length; i++) {
        buffer[i].clear();
        for (int j = 0; j < width * height; j++) {
          buffer[i].put(r).put(g).put(b);
        }
        buffer[i].rewind();
      }
    }
  }
}