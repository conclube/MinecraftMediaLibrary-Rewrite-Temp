package io.github.pulsebeat02.minecraftmedialibrary.image.gif;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;

/**
 * Class GifDecoder - Decodes a GIF file into one or more frames.
 *
 * <p>Example:
 *
 * <pre>{@code
 * GifDecoder d = new GifDecoder();
 * d.read("sample.gif");
 * int n = d.getFrameCount();
 * for (int i = 0; i < n; i++) {
 *    BufferedImage frame = d.getFrame(i);  // frame i
 *    int t = d.getDelay(i);  // display duration of frame in milliseconds
 *    // do something with frame
 * }
 * }</pre>
 *
 * No copyright asserted on the source code of this class. May be used for any purpose, however,
 * refer to the Unisys LZW patent for any additional restrictions. Please forward any corrections to
 * questions at fmsware.com.
 *
 * @author Kevin Weiner, FM Software; LZW decoder adapted from John Cristy's ImageMagick.
 * @version 1.03 November 2003
 */
public class GifDecoder {

  /** File read status: No errors. */
  public static final int STATUS_OK = 0;

  /** File read status: Error decoding file (may be partially decoded) */
  public static final int STATUS_FORMAT_ERROR = 1;

  /** File read status: Unable to open source. */
  public static final int STATUS_OPEN_ERROR = 2;

  protected static final int MaxStackSize = 4096;
  protected BufferedInputStream in;
  protected int status;
  protected int width; // full image width
  protected int height; // full image height
  protected boolean gctFlag; // global color table used
  protected int gctSize; // size of global color table
  protected int loopCount = 1; // iterations; 0 = repeat forever
  protected int[] gct; // global color table
  protected int[] lct; // local color table
  protected int[] act; // active color table
  protected int bgIndex; // background color index
  protected int bgColor; // background color
  protected int lastBgColor; // previous bg color
  protected int pixelAspect; // pixel aspect ratio
  protected boolean lctFlag; // local color table flag
  protected boolean interlace; // interlace flag
  protected int lctSize; // local color table size
  protected int ix, iy, iw, ih; // current image rectangle
  protected Rectangle lastRect; // last image rect
  protected BufferedImage image; // current frame
  protected BufferedImage lastImage; // previous frame
  protected byte[] block = new byte[256]; // current data block
  protected int blockSize = 0; // block size
  // last graphic control extension info
  protected int dispose = 0;
  // 0=no action; 1=leave in place; 2=restore to bg; 3=restore to prev
  protected int lastDispose = 0;
  protected boolean transparency = false; // use transparent color
  protected int delay = 0; // delay in milliseconds
  protected int transIndex; // transparent color index
  // max decoder pixel stack size
  // LZW decoder working arrays
  protected short[] prefix;
  protected byte[] suffix;
  protected byte[] pixelStack;
  protected byte[] pixels;

  protected ArrayList<GifFrame> frames; // frames read from current file
  protected int frameCount;

  /**
   * Gets display duration for specified frame.
   *
   * @param n int index of frame
   * @return delay in milliseconds
   */
  public int getDelay(final int n) {
    //
    this.delay = -1;
    if ((n >= 0) && (n < this.frameCount)) {
      this.delay = this.frames.get(n).delay;
    }
    return this.delay;
  }

  /**
   * Gets the number of frames read from file.
   *
   * @return frame count
   */
  public int getFrameCount() {
    return this.frameCount;
  }

  /**
   * Gets the first (or only) image read.
   *
   * @return BufferedImage containing first frame, or null if none.
   */
  public BufferedImage getImage() {
    return getFrame(0);
  }

  /**
   * Gets the "Netscape" iteration count, if any. A count of 0 means repeat indefinitiely.
   *
   * @return iteration count if one was specified, else 1.
   */
  public int getLoopCount() {
    return this.loopCount;
  }

  /**
   * Creates new frame image from current data (and previous frames as specified by their
   * disposition codes).
   */
  protected void setPixels() {
    // expose destination image's pixels as int array
    final int[] dest = ((DataBufferInt) this.image.getRaster().getDataBuffer()).getData();

    // fill in starting image contents based on last image's dispose code
    if (this.lastDispose > 0) {
      if (this.lastDispose == 3) {
        // use image before last
        final int n = this.frameCount - 2;
        if (n > 0) {
          this.lastImage = getFrame(n - 1);
        } else {
          this.lastImage = null;
        }
      }

      if (this.lastImage != null) {
        final int[] prev = ((DataBufferInt) this.lastImage.getRaster().getDataBuffer()).getData();
        System.arraycopy(prev, 0, dest, 0, this.width * this.height);
        // copy pixels

        if (this.lastDispose == 2) {
          // fill last image rect area with background color
          final Graphics2D g = this.image.createGraphics();
          final Color c;
          if (this.transparency) {
            c = new Color(0, 0, 0, 0); // assume background is transparent
          } else {
            c = new Color(this.lastBgColor); // use given background color
          }
          g.setColor(c);
          g.setComposite(AlphaComposite.Src); // replace area
          g.fill(this.lastRect);
          g.dispose();
        }
      }
    }

    // copy each source line to the appropriate place in the destination
    int pass = 1;
    int inc = 8;
    int iline = 0;
    for (int i = 0; i < this.ih; i++) {
      int line = i;
      if (this.interlace) {
        if (iline >= this.ih) {
          pass++;
          switch (pass) {
            case 2:
              iline = 4;
              break;
            case 3:
              iline = 2;
              inc = 4;
              break;
            case 4:
              iline = 1;
              inc = 2;
          }
        }
        line = iline;
        iline += inc;
      }
      line += this.iy;
      if (line < this.height) {
        final int k = line * this.width;
        int dx = k + this.ix; // start of line in dest
        int dlim = dx + this.iw; // end of dest line
        if ((k + this.width) < dlim) {
          dlim = k + this.width; // past dest edge
        }
        int sx = i * this.iw; // start of line in source
        while (dx < dlim) {
          // map color and insert in destination
          final int index = ((int) this.pixels[sx++]) & 0xff;
          final int c = this.act[index];
          if (c != 0) {
            dest[dx] = c;
          }
          dx++;
        }
      }
    }
  }

  /**
   * Gets the image contents of frame n.
   *
   * @return BufferedImage representation of frame, or null if n is invalid.
   */
  public BufferedImage getFrame(final int n) {
    BufferedImage im = null;
    if ((n >= 0) && (n < this.frameCount)) {
      im = this.frames.get(n).image;
    }
    return im;
  }

  /**
   * Gets image size.
   *
   * @return GIF image dimensions
   */
  public Dimension getFrameSize() {
    return new Dimension(this.width, this.height);
  }

  /**
   * Reads GIF image from stream
   *
   * @param is BufferedInputStream containing GIF file.
   * @return read status code (0 = no errors)
   */
  public int read(final BufferedInputStream is) {
    init();
    if (is != null) {
      this.in = is;
      readHeader();
      if (!err()) {
        readContents();
        if (this.frameCount < 0) {
          this.status = STATUS_FORMAT_ERROR;
        }
      }
    } else {
      this.status = STATUS_OPEN_ERROR;
    }
    try {
      is.close();
    } catch (final IOException ignored) {
    }
    return this.status;
  }

  /**
   * Reads GIF image from stream
   *
   * @param is InputStream containing GIF file.
   * @return read status code (0 = no errors)
   */
  public int read(InputStream is) {
    init();
    if (is != null) {
      if (!(is instanceof BufferedInputStream)) {
        is = new BufferedInputStream(is);
      }
      this.in = (BufferedInputStream) is;
      readHeader();
      if (!err()) {
        readContents();
        if (this.frameCount < 0) {
          this.status = STATUS_FORMAT_ERROR;
        }
      }
    } else {
      this.status = STATUS_OPEN_ERROR;
    }
    try {
      is.close();
    } catch (final IOException ignored) {
    }
    return this.status;
  }

  /**
   * Reads GIF file from specified file/URL source (URL assumed if name contains ":/" or "file:")
   *
   * @param name String containing source
   * @return read status code (0 = no errors)
   */
  public int read(String name) {
    this.status = STATUS_OK;
    try {
      name = name.trim().toLowerCase();
      if ((name.contains("file:")) || (name.indexOf(":/") > 0)) {
        final URL url = new URL(name);
        this.in = new BufferedInputStream(url.openStream());
      } else {
        this.in = new BufferedInputStream(new FileInputStream(name));
      }
      this.status = read(this.in);
    } catch (final IOException e) {
      this.status = STATUS_OPEN_ERROR;
    }

    return this.status;
  }

  /** Decodes LZW image data into pixel array. Adapted from John Cristy's ImageMagick. */
  protected void decodeImageData() {
    final int NullCode = -1;
    final int npix = this.iw * this.ih;
    int available;
    final int clear;
    int code_mask;
    int code_size;
    final int end_of_information;
    int in_code;
    int old_code;
    int bits;
    int code;
    int count;
    int i;
    int datum;
    final int data_size;
    int first;
    int top;
    int bi;
    int pi;

    if ((this.pixels == null) || (this.pixels.length < npix)) {
      this.pixels = new byte[npix]; // allocate new pixel array
    }
    if (this.prefix == null) {
      this.prefix = new short[MaxStackSize];
    }
    if (this.suffix == null) {
      this.suffix = new byte[MaxStackSize];
    }
    if (this.pixelStack == null) {
      this.pixelStack = new byte[MaxStackSize + 1];
    }

    //  Initialize GIF data stream decoder.

    data_size = read();
    clear = 1 << data_size;
    end_of_information = clear + 1;
    available = clear + 2;
    old_code = NullCode;
    code_size = data_size + 1;
    code_mask = (1 << code_size) - 1;
    for (code = 0; code < clear; code++) {
      this.prefix[code] = 0;
      this.suffix[code] = (byte) code;
    }

    //  Decode GIF pixel stream.

    datum = bits = count = first = top = pi = bi = 0;

    for (i = 0; i < npix; ) {
      if (top == 0) {
        if (bits < code_size) {
          //  Load bytes until there are enough bits for a code.
          if (count == 0) {
            // Read a new data block.
            count = readBlock();
            if (count <= 0) {
              break;
            }
            bi = 0;
          }
          datum += (((int) this.block[bi]) & 0xff) << bits;
          bits += 8;
          bi++;
          count--;
          continue;
        }

        //  Get the next code.

        code = datum & code_mask;
        datum >>= code_size;
        bits -= code_size;

        //  Interpret the code

        if ((code > available) || (code == end_of_information)) {
          break;
        }
        if (code == clear) {
          //  Reset decoder.
          code_size = data_size + 1;
          code_mask = (1 << code_size) - 1;
          available = clear + 2;
          old_code = NullCode;
          continue;
        }
        if (old_code == NullCode) {
          this.pixelStack[top++] = this.suffix[code];
          old_code = code;
          first = code;
          continue;
        }
        in_code = code;
        if (code == available) {
          this.pixelStack[top++] = (byte) first;
          code = old_code;
        }
        while (code > clear) {
          this.pixelStack[top++] = this.suffix[code];
          code = this.prefix[code];
        }
        first = ((int) this.suffix[code]) & 0xff;

        //  Add a new string to the string table,

        if (available >= MaxStackSize) {
          this.pixelStack[top++] = (byte) first;
          continue;
        }
        this.pixelStack[top++] = (byte) first;
        this.prefix[available] = (short) old_code;
        this.suffix[available] = (byte) first;
        available++;
        if (((available & code_mask) == 0) && (available < MaxStackSize)) {
          code_size++;
          code_mask += available;
        }
        old_code = in_code;
      }

      //  Pop a pixel off the pixel stack.

      top--;
      this.pixels[pi++] = this.pixelStack[top];
      i++;
    }

    for (i = pi; i < npix; i++) {
      this.pixels[i] = 0; // clear missing pixels
    }
  }

  /** Returns true if an error was encountered during reading/decoding */
  protected boolean err() {
    return this.status != STATUS_OK;
  }

  /** Initializes or re-initializes reader */
  protected void init() {
    this.status = STATUS_OK;
    this.frameCount = 0;
    this.frames = new ArrayList<>();
    this.gct = null;
    this.lct = null;
  }

  /** Reads a single byte from the input stream. */
  protected int read() {
    int curByte = 0;
    try {
      curByte = this.in.read();
    } catch (final IOException e) {
      this.status = STATUS_FORMAT_ERROR;
    }
    return curByte;
  }

  /**
   * Reads next variable length block from input.
   *
   * @return number of bytes stored in "buffer"
   */
  protected int readBlock() {
    this.blockSize = read();
    int n = 0;
    if (this.blockSize > 0) {
      try {
        int count;
        while (n < this.blockSize) {
          count = this.in.read(this.block, n, this.blockSize - n);
          if (count == -1) {
            break;
          }
          n += count;
        }
      } catch (final IOException ignored) {
      }

      if (n < this.blockSize) {
        this.status = STATUS_FORMAT_ERROR;
      }
    }
    return n;
  }

  /**
   * Reads color table as 256 RGB integer values
   *
   * @param ncolors int number of colors to read
   * @return int array containing 256 colors (packed ARGB with full alpha)
   */
  protected int[] readColorTable(final int ncolors) {
    final int nbytes = 3 * ncolors;
    int[] tab = null;
    final byte[] c = new byte[nbytes];
    int n = 0;
    try {
      n = this.in.read(c);
    } catch (final IOException ignored) {
    }
    if (n < nbytes) {
      this.status = STATUS_FORMAT_ERROR;
    } else {
      tab = new int[256]; // max size to avoid bounds checks
      int i = 0;
      int j = 0;
      while (i < ncolors) {
        final int r = ((int) c[j++]) & 0xff;
        final int g = ((int) c[j++]) & 0xff;
        final int b = ((int) c[j++]) & 0xff;
        tab[i++] = 0xff000000 | (r << 16) | (g << 8) | b;
      }
    }
    return tab;
  }

  /** Main file parser. Reads GIF content blocks. */
  protected void readContents() {
    // read GIF file content blocks
    boolean done = false;
    while (!(done || err())) {
      int code = read();
      switch (code) {
        case 0x2C: // image separator
          readImage();
          break;

        case 0x21: // extension
          code = read();
          switch (code) {
            case 0xf9: // graphics control extension
              readGraphicControlExt();
              break;

            case 0xff: // application extension
              readBlock();
              final StringBuilder app = new StringBuilder();
              for (int i = 0; i < 11; i++) {
                app.append((char) this.block[i]);
              }
              if (app.toString().equals("NETSCAPE2.0")) {
                readNetscapeExt();
              } else {
                skip(); // don't care
              }
              break;

            default: // uninteresting extension
              skip();
          }
          break;

        case 0x3b: // terminator
          done = true;
          break;

        case 0x00: // bad byte, but keep going and see what happens
          break;

        default:
          this.status = STATUS_FORMAT_ERROR;
      }
    }
  }

  /** Reads Graphics Control Extension values */
  protected void readGraphicControlExt() {
    read(); // block size
    final int packed = read(); // packed fields
    this.dispose = (packed & 0x1c) >> 2; // disposal method
    if (this.dispose == 0) {
      this.dispose = 1; // elect to keep old image if discretionary
    }
    this.transparency = (packed & 1) != 0;
    this.delay = readShort() * 10; // delay in milliseconds
    this.transIndex = read(); // transparent color index
    read(); // block terminator
  }

  /** Reads GIF file header information. */
  protected void readHeader() {
    final StringBuilder id = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      id.append((char) read());
    }
    if (!id.toString().startsWith("GIF")) {
      this.status = STATUS_FORMAT_ERROR;
      return;
    }

    readLSD();
    if (this.gctFlag && !err()) {
      this.gct = readColorTable(this.gctSize);
      this.bgColor = this.gct[this.bgIndex];
    }
  }

  /** Reads next frame image */
  protected void readImage() {
    this.ix = readShort(); // (sub)image position & size
    this.iy = readShort();
    this.iw = readShort();
    this.ih = readShort();

    final int packed = read();
    this.lctFlag = (packed & 0x80) != 0; // 1 - local color table flag
    this.interlace = (packed & 0x40) != 0; // 2 - interlace flag
    // 3 - sort flag
    // 4-5 - reserved
    this.lctSize = 2 << (packed & 7); // 6-8 - local color table size

    if (this.lctFlag) {
      this.lct = readColorTable(this.lctSize); // read table
      this.act = this.lct; // make local table active
    } else {
      this.act = this.gct; // make global table active
      if (this.bgIndex == this.transIndex) {
        this.bgColor = 0;
      }
    }
    int save = 0;
    if (this.transparency) {
      save = this.act[this.transIndex];
      this.act[this.transIndex] = 0; // set transparent color if specified
    }

    if (this.act == null) {
      this.status = STATUS_FORMAT_ERROR; // no color table defined
    }

    if (err()) {
      return;
    }

    decodeImageData(); // decode pixel data
    skip();

    if (err()) {
      return;
    }

    this.frameCount++;

    // create new image to receive frame data
    this.image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB_PRE);

    setPixels(); // transfer pixel data to image

    this.frames.add(new GifFrame(this.image, this.delay)); // add image to frame list

    if (this.transparency) {
      this.act[this.transIndex] = save;
    }
    resetFrame();
  }

  /** Reads Logical Screen Descriptor */
  protected void readLSD() {

    // logical screen size
    this.width = readShort();
    this.height = readShort();

    // packed fields
    final int packed = read();
    this.gctFlag = (packed & 0x80) != 0; // 1   : global color table flag
    // 2-4 : color resolution
    // 5   : gct sort flag
    this.gctSize = 2 << (packed & 7); // 6-8 : gct size

    this.bgIndex = read(); // background color index
    this.pixelAspect = read(); // pixel aspect ratio
  }

  /** Reads Netscape extenstion to obtain iteration count */
  protected void readNetscapeExt() {
    do {
      readBlock();
      if (this.block[0] == 1) {
        // loop count sub-block
        final int b1 = ((int) this.block[1]) & 0xff;
        final int b2 = ((int) this.block[2]) & 0xff;
        this.loopCount = (b2 << 8) | b1;
      }
    } while ((this.blockSize > 0) && !err());
  }

  /** Reads next 16-bit value, LSB first */
  protected int readShort() {
    // read 16-bit value, LSB first
    return read() | (read() << 8);
  }

  /** Resets frame state for reading next image. */
  protected void resetFrame() {
    this.lastDispose = this.dispose;
    this.lastRect = new Rectangle(this.ix, this.iy, this.iw, this.ih);
    this.lastImage = this.image;
    this.lastBgColor = this.bgColor;
    this.lct = null;
  }

  /** Skips variable length blocks up to and including next zero length block. */
  protected void skip() {
    do {
      readBlock();
    } while ((this.blockSize > 0) && !err());
  }

  static class GifFrame {
    public BufferedImage image;
    public int delay;

    public GifFrame(final BufferedImage im, final int del) {
      this.image = im;
      this.delay = del;
    }
  }
}
