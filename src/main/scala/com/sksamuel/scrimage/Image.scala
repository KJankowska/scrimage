package com.sksamuel.scrimage

import java.awt.Graphics2D
import com.mortennobel.imagescaling.{ResampleFilters, ResampleOp}
import java.awt.geom.AffineTransform
import java.io.{InputStream, OutputStream, File}
import com.sksamuel.scrimage.Format.PNG
import com.sksamuel.scrimage.ScaleMethod._
import com.sksamuel.scrimage.Centering.Center
import javax.imageio.ImageIO
import org.apache.commons.io.FileUtils
import java.awt.image.{BufferedImageOp, AffineTransformOp, DataBufferByte, BufferedImage}
import com.sksamuel.scrimage.Color.White

/** @author Stephen Samuel
  *
  *         RichImage is class that represents an in memory image.
  *
  **/
class Image(val awt: BufferedImage) {
    require(awt != null, "Wrapping image cannot be null")
    require(awt.getType == Image.CANONICAL_DATA_TYPE,
        "Unsupported underlying image type. Consider using Image.apply(java.awt.Image) in order to wrap the image in the right data type")

    lazy val width: Int = awt.getWidth(null)
    lazy val height: Int = awt.getHeight(null)
    lazy val dimensions: (Int, Int) = (width, height)
    lazy val ratio: Double = if (height == 0) 0 else width / height.toDouble

    // creates a new raw image that is the same size as this image and uses the canonical data model
    def _raw: BufferedImage = Image.raw(awt)

    /**
     *
     * @param point the coordinates of the pixel to grab
     *
     * @return
     */
    def pixel(point: (Int, Int)): Int = {
        val pixels = awt.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
        val pixelIndex = (width * point._2 + point._1)
        pixels(pixelIndex)
    }

    def pixels: Array[Byte] = {
        awt.getRaster.getDataBuffer match {
            case buffer: DataBufferByte => buffer.getData
            case _ => throw new UnsupportedOperationException
        }
    }

    /**
     *
     * Applies the given filter to this image and returns the modified image.
     *
     * @param filter the filter to apply. See Filter.
     *
     * @return A new image with the given filter applied.
     */
    def filter(filter: Filter): Image = filter.apply(this)

    /**
     * Copies this image and returns a new image with a new backing array. Any operations to the copied image will
     * not affect the original. Images can be copied multiple times.
     *
     * @return A clone of this image.
     */
    def copy = Image.copy(awt)

    /**
     *
     * @return A new image that is the result of flipping this image horizontally.
     */
    def flipX: Image = {
        val tx = AffineTransform.getScaleInstance(-1, 1)
        tx.translate(-width, 0)
        _flip(tx)
    }

    /**
     *
     * @return A new image that is the result of flipping this image vertically.
     */
    def flipY: Image = {
        val tx = AffineTransform.getScaleInstance(1, -1)
        tx.translate(0, -height)
        _flip(tx)
    }

    private[scrimage] def _flip(tx: AffineTransform): Image = {
        val op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR)
        val flipped = op.filter(awt, null)
        new Image(flipped)
    }

    def rotateLeft = _rotate(Math.PI)
    def rotateRight = _rotate(-Math.PI)

    def _rotate(angle: Double): Image = {
        val target = new BufferedImage(height, width, awt.getType)
        val g2 = target.getGraphics.asInstanceOf[Graphics2D]
        g2.rotate(angle)
        g2.drawImage(awt, 0, 0, null)
        g2.dispose()
        new Image(target)
    }

    /**
     *
     * Returns a copy of the image which has been scaled to fit
     * inside the current dimensions whilst keeping the aspect ratio.
     *
     * @param width the target size
     * @param height the target size
     *
     * @return
     */
    def fit(width: Int, height: Int, scaleMethod: ScaleMethod = Bicubic, centering: Centering = Center): Image = copy // todo

    /**
     *
     * That is the canvas will have the given dimensions but the image will not necessarily cover it all.
     *
     * @param width the target width
     * @param height the target height
     * @param scaleMethod the type of scaling method to use. Defaults to Bicubic
     *
     * @return
     */
    def cover(width: Int, height: Int, scaleMethod: ScaleMethod = Bicubic): Image = copy // todo

    def scale(scaleFactor: Double): Image = scale(scaleFactor, Bicubic)

    /**
     *
     * Scale will resize the canvas and the image. This is like a "image resize" in Photoshop.
     *
     * @param scaleFactor the target increase or decrease. 1 is the same as original.
     * @param scaleMethod the type of scaling method to use.
     *
     * @return a new Image that is the result of scaling this image
     */
    def scale(scaleFactor: Double, scaleMethod: ScaleMethod): Image =
        scale((width * scaleFactor).toInt, (height * scaleFactor).toInt, scaleMethod)

    val SCALE_THREADS = 2

    /**
     *
     * Scale will resize the canvas and the image. This is like a "image resize" in Photoshop.
     *
     * @param width the target width
     * @param height the target height
     * @param scaleMethod the type of scaling method to use. Defaults to SmoothScale
     *
     * @return a new Image that is the result of scaling this image
     */
    def scale(width: Int, height: Int, scaleMethod: ScaleMethod = Bicubic): Image = {
        val op = new ResampleOp(width, height)
        op.setNumberOfThreads(SCALE_THREADS)
        scaleMethod match {
            case FastScale =>
            case Bicubic => op.setFilter(ResampleFilters.getBiCubicFilter)
            case Bilinear => op.setFilter(ResampleFilters.getTriangleFilter)
            case BSpline => op.setFilter(ResampleFilters.getBSplineFilter)
            case Lanczos3 => op.setFilter(ResampleFilters.getLanczos3Filter)
        }
        val scaled = op.filter(awt, null)
        new Image(scaled)
    }

    def resize(scaleFactor: Double): Image = resize(scaleFactor, Center)
    def resize(scaleFactor: Double, centering: Centering): Image =
        resize((width * scaleFactor).toInt, (height * scaleFactor).toInt, centering)

    /**
     *
     * Resize will resize the canvas, it will not scale the image. This is like a "canvas resize" in Photoshop.
     * If the dimensions are smaller than the current canvas size then the image will be cropped.
     *
     * @param width the target width
     * @param height the target height
     * @param centering where to position the original image after the canvas size change
     *
     * @return a new Image that is the result of resizing the canvas.
     */
    def resize(width: Int, height: Int, centering: Centering = Center): Image = {
        val target = new BufferedImage(width, height, Image.CANONICAL_DATA_TYPE)
        target.getGraphics.asInstanceOf[Graphics2D].drawImage(awt, 0, 0, null)
        new Image(target)
    }

    def pad(size: Int): Image = pad(size, Color.White)
    def pad(size: Int, color: com.sksamuel.scrimage.Color): Image = pad(width + size * 2, height + size * 2, color)

    /**
     *
     * Creates a new image which is the result of this image padded to the canvas size specified.
     * If this image is already larger than the specified pad then the sizes of the existing
     * image will be used instead.
     *
     * Eg, requesting a pad of 200,200 on an image of 250,300 will result
     * in keeping the 250,300. Eg2, requesting a pad of 300,300 on an image of 400,250 will result
     * in the width staying at 400 and the height padded to 300.

     * @param preferredWidth the size of the output canvas width
     * @param preferredHeight the size of the output canvas height
     * @param color the background of the padded area.
     *
     * @return A new image that is the result of the padding
     */
    def pad(preferredWidth: Int, preferredHeight: Int, color: com.sksamuel.scrimage.Color = White): Image = {
        val w = if (width < preferredWidth) preferredWidth else width
        val h = if (height < preferredHeight) preferredHeight else height
        val filled = Image.filled(w, h, color)
        val g = filled.awt.getGraphics
        g.drawImage(awt, ((w - width) / 2.0).toInt, ((h - height) / 2.0).toInt, null)
        g.dispose()
        filled
    }

    def filled(color: com.sksamuel.scrimage.Color): Image = filled(color.value)
    def filled(color: Int): Image = filled(new java.awt.Color(color))
    def filled(color: java.awt.Color): Image = Image.filled(width, height, color)

    def write(file: File) {
        write(file, PNG)
    }

    def write(file: File, format: Format) {
        ImageWriter(file).write(this, format)
    }

    def write(out: OutputStream) {
        write(out, PNG)
    }

    def write(out: OutputStream, format: Format) {
        ImageWriter(out).write(this, format)
    }

    override def hashCode(): Int = awt.hashCode
    override def equals(obj: Any): Boolean = obj match {
        case other: Image => other.pixels.sameElements(pixels)
        case _ => false
    }
}

object Image {

    val CANONICAL_DATA_TYPE = BufferedImage.TYPE_4BYTE_ABGR

    def apply(in: InputStream): Image = {
        require(in != null)
        require(in.available > 0)
        apply(ImageIO.read(in))
    }

    def apply(file: File): Image = {
        require(file != null)
        val in = FileUtils.openInputStream(file)
        apply(in)
    }

    def apply(awt: java.awt.Image): Image = {
        require(awt != null, "Input image cannot be null")
        awt match {
            case buff: BufferedImage if buff.getType == CANONICAL_DATA_TYPE => new Image(buff)
            case _ => copy(awt)
        }
    }

    def copy(awt: java.awt.Image) = {
        require(awt != null, "Input image cannot be null")
        val buff = raw(awt)
        val g2 = buff.getGraphics
        g2.drawImage(awt, 0, 0, null)
        g2.dispose()
        new Image(buff)
    }

    def filled(width: Int, height: Int, color: com.sksamuel.scrimage.Color): Image = filled(width, height, color.value)
    def filled(width: Int, height: Int, color: Int): Image = filled(width, height, new java.awt.Color(color))
    def filled(width: Int, height: Int, color: java.awt.Color): Image = {
        val awt = new BufferedImage(width, height, Image.CANONICAL_DATA_TYPE)
        val g2 = awt.getGraphics
        g2.setColor(color)
        g2.fillRect(0, 0, awt.getWidth, awt.getHeight)
        g2.dispose()
        new Image(awt)
    }

    def raw(awt: java.awt.Image): BufferedImage = raw(awt.getWidth(null), awt.getHeight(null))
    def raw(width: Int, height: Int): BufferedImage = new BufferedImage(width, height, CANONICAL_DATA_TYPE)
    def empty(width: Int, height: Int): Image = new Image(new BufferedImage(width, height, CANONICAL_DATA_TYPE))
}

sealed trait ScaleMethod
object ScaleMethod {
    object FastScale extends ScaleMethod
    object Lanczos3 extends ScaleMethod
    object BSpline extends ScaleMethod
    object Bilinear extends ScaleMethod
    object Bicubic extends ScaleMethod
}

sealed trait Centering
object Centering {
    object Center extends Centering
    object TopLeft extends Centering
    object TopRight extends Centering
    object Top extends Centering
    object Left extends Centering
    object Right extends Centering
    object Bottom extends Centering
    object BottomLeft extends Centering
    object BottomRight extends Centering
}

trait Filter {
    def apply(image: Image): Image
}

/**
 * Extension of Filter that applies its filters using a standard java BufferedImageOp
 */
trait BufferedOpFilter extends Filter {
    val op: BufferedImageOp
    def apply(image: Image): Image = {
        val target = image._raw
        val g2 = target.getGraphics.asInstanceOf[Graphics2D]
        g2.drawImage(image.awt, op, 0, 0)
        g2.dispose()
        new Image(target)
    }
}

object Implicits {
    implicit def awt2rich(awt: java.awt.Image) = Image(awt)
}


