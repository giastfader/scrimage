/*
   Copyright 2013-2014 Stephen K Samuel

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.sksamuel.scrimage

import java.awt.Color
import java.awt.image.{AffineTransformOp, BufferedImage}
import thirdparty.mortennobel.{ResampleOp, ResampleFilters}
import sun.awt.resources.awt
import javax.imageio.ImageIO
import java.io.{File, ByteArrayInputStream, InputStream}
import org.apache.commons.io.{IOUtils, FileUtils}
import com.sksamuel.scrimage.ScaleMethod.Bicubic
import com.sksamuel.scrimage.Position.Center
import com.sksamuel.scrimage.io.ImageWriter
import scala.concurrent.ExecutionContext

/**
 * @author Stephen Samuel
 *
 *         Image is class that represents an in memory image.
 *
 */
class Image(val raster: Raster) extends ImageLike[Image] with WritableImageLike {
  require(raster != null, "Raster cannot be null")

  val width: Int = raster.width
  val height: Int = raster.height

  override def empty: Image = Image.empty(width, height)
  override def copy: Image = new Image(raster.copy)

  override def map(f: (Int, Int, Int) => Int): Image = {
    val target = copy
    target
  }

  /**
   * Create a new image by drawing the specified image over the top of the current image.
   * If the given image is larger then the excess pixels will be ignored.
   * If the given image is smaller than the current image will be used for the remaining space.
   */
  def draw(image: Image): Image = draw(0, 0, image)
  def draw(x: Int, y: Int, image: Image): Image = {
    new Image(raster.draw(x, y, image.raster))
  }

  /**
   * Removes the given amount of pixels from each edge; like a crop operation.
   *
   * @param amount the number of pixels to trim from each edge
   *
   * @return a new Image with the dimensions width-trim*2,height-trim*2
   */
  def trim(amount: Int): Image = trim(amount, amount, amount, amount)
  def trim(left: Int, top: Int, right: Int, bottom: Int): Image = draw(-left, -top, this)

  /**
   * Returns a new Image that is a subimage or region of the original image.
   *
   * @param x the start x coordinate
   * @param y the start y coordinate
   * @param w the width of the subimage
   * @param h the height of the subimage
   * @return a new Image that is the subimage
   */
  def subimage(x: Int, y: Int, w: Int, h: Int): Image = new Image(raster.subraster(x, y, w, h))

  /**
   * Returns the pixel at the given coordinates as a integer in RGB format.
   *
   * @param x the x coordinate of the pixel to grab
   * @param y the y coordinate of the pixel to grab
   *
   * @return the ARGB value of the pixel
   */
  @deprecated("use pixel method on raster", "2.0")
  def pixel(x: Int, y: Int): Int = raster.pixel(x, y)

  /**
   * Uses linear interpolation to get a sub-pixel.
   *
   * Legal values for `x` and `y` are in [0, width) and [0, height),
   * respectively.
   */
  def subpixel(x: Double, y: Double): Int = {
    require(x >= 0 && x < width && y >= 0 && y < height)

    // As a part of linear interpolation, determines the integer coordinates
    // of the pixel's neighbors, as well as the amount of weight each should
    // get in the weighted average.
    // Operates on one dimension at a time.
    def integerPixelCoordinatesAndWeights(
                                           double: Double,
                                           numPixels: Int): List[Tuple2[Int, Double]] = {
      if (double <= 0.5) List((0, 1.0))
      else if (double >= numPixels - 0.5) List((numPixels - 1, 1.0))
      else {
        val shifted = double - 0.5
        val floor = shifted.floor
        val floorWeight = 1 - (shifted - floor)
        val ceil = shifted.ceil
        val ceilWeight = 1 - floorWeight
        assert(floorWeight + ceilWeight == 1)
        List((floor.toInt, floorWeight), (ceil.toInt, ceilWeight))
      }
    }

    val xIntsAndWeights = integerPixelCoordinatesAndWeights(x, width)
    val yIntsAndWeights = integerPixelCoordinatesAndWeights(y, height)

    // These are the summands in the weighted averages.
    // Note there are 4 weighted averages: one for each channel (a, r, g, b).
    val summands = for (
      (xInt, xWeight) <- xIntsAndWeights;
      (yInt, yWeight) <- yIntsAndWeights
    ) yield {
      val weight = xWeight * yWeight
      if (weight == 0) List(0.0, 0.0, 0.0, 0.0)
      else {
        val pixelInt = pixel(xInt, yInt)

        List(
          weight * PixelTools.alpha(pixelInt),
          weight * PixelTools.red(pixelInt),
          weight * PixelTools.green(pixelInt),
          weight * PixelTools.blue(pixelInt))
      }
    }

    // We perform the weighted averaging (a summation).
    // First though, we need to transpose so that we sum within channels,
    // not within pixels.
    val List(a, r, g, b) = summands.transpose.map(_.sum)

    PixelTools.argb(a.round.toInt, r.round.toInt, g.round.toInt, b.round.toInt)
  }

  /**
   * Extracts a subimage, but using subpixel interpolation.
   */
  def subpixelSubimage(x: Double,
                       y: Double,
                       subWidth: Int,
                       subHeight: Int): Image = {
    require(x >= 0)
    require(x + subWidth < width)
    require(y >= 0)
    require(y + subHeight < height)
    val subimage = new Image(Raster.apply(subWidth, subHeight)).toMutable

    // Simply copy the pixels over, one by one.
    for (
      yIndex <- 0 until subHeight;
      xIndex <- 0 until subWidth
    ) {
      subimage.setPixel(xIndex, yIndex, subpixel(xIndex + x, yIndex + y))
    }

    new Image(null) // todo update mutable images to not use AWT
  }

  /**
   * Extract a patch, centered at a subpixel point.
   */
  def subpixelSubimageCenteredAtPoint(x: Double,
                                      y: Double,
                                      xRadius: Double,
                                      yRadius: Double): Image = {
    val xWidth = 2 * xRadius
    val yWidth = 2 * yRadius

    // The dimensions of the extracted patch must be integral.
    require(xWidth == xWidth.round)
    require(yWidth == yWidth.round)

    subpixelSubimage(
      x - xRadius,
      y - yRadius,
      xWidth.round.toInt,
      yWidth.round.toInt)
  }

  /**
   * Returns all the patches of a given size in the image, assuming pixel
   * alignment (no subpixel extraction).
   *
   * The patches are returned as a sequence of closures.
   */
  def patches(patchWidth: Int, patchHeight: Int): IndexedSeq[() => Image] =
    for (
      row <- 0 to height - patchHeight;
      col <- 0 to width - patchWidth
    ) yield {
      () => subimage(col, row, patchWidth, patchHeight)
    }

  /**
   * Returns the pixels of this image represented as an array of Integers.
   *
   * @return
   */
  @deprecated("use the raster to get pixel level information")
  def pixels: Array[Int] = raster match {
    case iraster: IntARGBRaster => iraster.pixels
    case braster: ByteARGBRaster => null
  }

  /**
   * Apply the given image with this image using the given composite.
   * The original image is unchanged.
   *
   * @param composite the composite to use. See com.sksamuel.scrimage.Composite.
   * @param applicative the image to apply with the composite.
   *
   * @return A new image with the given image applied using the given composite.
   */
  def composite(composite: Composite, applicative: Image): Image = {
    val copy = this.copy
    composite.apply(copy, applicative)
    copy
  }

  /**
   * Creates a copy of this image with the given filter applied.
   * The original (this) image is unchanged.
   *
   * @param filter the filter to apply. See com.sksamuel.scrimage.Filter.
   *
   * @return A new image with the given filter applied.
   */
  def filter(filter: Filter): Image = {
    val target = copy
    filter.apply(target)
    target
  }

  /**
   * Apply a sequence of filters in sequence.
   * This is sugar for image.filter(filter1).filter(filter2)....
   *
   * @param filters the sequence filters to apply
   * @return the result of applying each filter in turn
   */
  def filter(filters: Filter*): Image = filters.foldLeft(this)((image, filter) => image.filter(filter))

  def removeTransparency(color: java.awt.Color): Image = {
    val rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = rgb.createGraphics()
    g.drawImage(awt, 0, 0, color, null)
    new Image(rgb)
  }

  /**
   * Flips this image horizontally.
   *
   * @return The result of flipping this image horizontally.
   */
  def flipX: Image = new Image(raster.flipX)

  /**
   * Flips this image vertically.
   *
   * @return The result of flipping this image vertically.
   */
  def flipY: Image = new Image(raster.flipY)

  /**
   * Returns a copy of this image rotated 90 degrees anti-clockwise (counter clockwise to US English speakers).
   *
   * @return
   */
  def rotateLeft = _rotate(Math.PI / 2)

  /**
   * Returns a copy of this image rotated 90 degrees clockwise.
   *
   * @return
   */
  def rotateRight = _rotate(-Math.PI / 2)

  def _rotate(angle: Double): Image = {
    throw new UnsupportedOperationException("need to update to not use awt")
    //    val target = new BufferedImage(height, width, awt.getType)
    //    val g2 = target.getGraphics.asInstanceOf[Graphics2D]
    //    val offset = angle match {
    //      case a if a < 0 => (0, width)
    //      case a if a > 0 => (height, 0)
    //      case _ => (0, 0)
    //    }
    //
    //    g2.translate(offset._1, offset._2)
    //    g2.rotate(angle)
    //    g2.drawImage(awt, 0, 0, null)
    //    g2.dispose()
    //    new Image(target)
  }

  /**
   *
   * Returns a copy of this image with the given dimensions
   * where the original image has been scaled to fit completely
   * inside the new dimensions whilst retaining the original aspect ratio.
   *
   * @param targetWidth the target width
   * @param targetHeight the target height
   * @param scaleMethod the algorithm to use for the scaling operation. See ScaleMethod.
   * @param color the color to use as the "padding" colour should the scaled original not fit exactly inside the new dimensions
   * @param position where to position the image inside the new canvas
   *
   * @return a new Image with the original image scaled to fit inside
   */
  def fit(targetWidth: Int,
          targetHeight: Int,
          color: java.awt.Color = java.awt.Color.WHITE,
          scaleMethod: ScaleMethod = Bicubic,
          position: Position = Center): Image = {
    val fittedDimensions = ImageTools.dimensionsToFit((targetWidth, targetHeight), (width, height))
    val scaled = scaleTo(fittedDimensions._1, fittedDimensions._2, scaleMethod)
    val target = Image.filled(targetWidth, targetHeight, color)
    val x = ((targetWidth - fittedDimensions._1) / 2.0).toInt
    val y = ((targetHeight - fittedDimensions._2) / 2.0).toInt
    g2.drawImage(scaled.awt, x, y, null)
    target
  }

  /**
   *
   * Returns a copy of the canvas with the given dimensions where the
   * original image has been scaled to completely cover the new dimensions
   * whilst retaining the original aspect ratio.
   *
   * If the new dimensions have a different aspect ratio than the old image
   * then the image will be cropped so that it still covers the new area
   * without leaving any background.
   *
   * @param targetWidth the target width
   * @param targetHeight the target height
   * @param scaleMethod the type of scaling method to use. Defaults to Bicubic
   * @param position where to position the image inside the new canvas
   *
   * @return a new Image with the original image scaled to cover the new dimensions
   */
  def cover(targetWidth: Int,
            targetHeight: Int,
            scaleMethod: ScaleMethod = Bicubic,
            position: Position = Center): Image = {
    val coveredDimensions = ImageTools.dimensionsToCover((targetWidth, targetHeight), (width, height))
    val scaled = scaleTo(coveredDimensions._1, coveredDimensions._2, scaleMethod)
    val x = ((targetWidth - coveredDimensions._1) / 2.0).toInt
    val y = ((targetHeight - coveredDimensions._2) / 2.0).toInt

    val target = Image.empty(targetWidth, targetHeight)
    g2.drawImage(scaled.awt, x, y, null)
    g2.dispose()
    target
  }

  /**
   *
   * Scale will resize both the canvas and the image.
   * This is like a "image resize" in Photoshop.
   *
   * The size of the scaled instance are taken from the given
   * width and height parameters.
   *
   * @param targetWidth the target width
   * @param targetHeight the target height
   * @param scaleMethod the type of scaling method to use. Defaults to SmoothScale
   *
   * @return a new Image that is the result of scaling this image
   */
  def scaleTo(targetWidth: Int, targetHeight: Int, scaleMethod: ScaleMethod = Bicubic): Image = {
    throw new UnsupportedOperationException("need to update to not use AWT methods")
    //    scaleMethod match {
    //      case FastScale =>
    //        val target = Image.empty(targetWidth, targetHeight)
    //        val g2 = target.awt.getGraphics.asInstanceOf[Graphics2D]
    //        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    //        g2.drawImage(awt, 0, 0, targetWidth, targetHeight, null)
    //        g2.dispose()
    //        target
    //      case _ =>
    //        val method = scaleMethod match {
    //          case Bilinear => ResampleFilters.triangleFilter
    //          case BSpline => ResampleFilters.bSplineFilter
    //          case Lanczos3 => ResampleFilters.lanczos3Filter
    //          case _ => ResampleFilters.biCubicFilter
    //        }
    //        val op = new ResampleOp(Image.SCALE_THREADS, method, targetWidth, targetHeight)
    //        val scaled = op.filter(awt, null)
    //        Image(scaled)
    //    }
  }

  /**
   *
   * Resize will resize the canvas, it will not scale the image.
   * This is like a "canvas resize" in Photoshop.
   *
   * If the dimensions are smaller than the current canvas size
   * then the image will be cropped.
   *
   * The position parameter determines how the original image will be positioned on the new
   * canvas.
   *
   * @param targetWidth the target width
   * @param targetHeight the target height
   * @param position where to position the original image after the canvas size change
   * @param background the background color if the canvas was enlarged
   *
   * @return a new Image that is the result of resizing the canvas.
   */
  def resizeTo(targetWidth: Int,
               targetHeight: Int,
               position: Position = Center,
               background: Color = Color.WHITE): Image = {
    if (targetWidth == width && targetHeight == height) this
    else {
      val target = Image.filled(targetWidth, targetHeight, background)
      val (x, y) = position.calculateXY(targetWidth, targetHeight, width, height)
      target.draw(x, y, this)
    }
  }

  /**
   * Crops an image by removing cols and rows that are composed only of a single
   * given color.
   *
   * Eg, if an image had a 20 pixel border of white at the top, and this method was
   * invoked with Color.White then the image returned would have that 20 pixel border
   * removed.
   *
   * This method is useful when images have an abudance of
   *
   * @param color the color to match
   * @return
   */
  def autocrop(color: Color): Image = {
    def uniform(color: Color, pixels: Array[Int]) = pixels.forall(p => p == color.getRGB)
    def scanright(col: Int, image: Image): Int = {
      if (uniform(color, pixels(col, 0, 1, height))) scanright(col + 1, image)
      else col
    }
    def scanleft(col: Int, image: Image): Int = {
      if (uniform(color, pixels(col, 0, 1, height))) scanleft(col - 1, image)
      else col
    }
    def scandown(row: Int, image: Image): Int = {
      if (uniform(color, pixels(0, row, width, 1))) scandown(row + 1, image)
      else row
    }
    def scanup(row: Int, image: Image): Int = {
      if (uniform(color, pixels(0, row, width, 1))) scanup(row - 1, image)
      else row
    }
    val x1 = scanright(0, this)
    val x2 = scanleft(width - 1, this)
    val y1 = scandown(0, this)
    val y2 = scanup(height - 1, this)
    subimage(x1, y1, x2 - x1, y2 - y1)
  }

  /**
   * Creates a new image which is the result of this image padded to the canvas size specified.
   * If this image is already larger than the specified pad then the sizes of the existing
   * image will be used instead.
   *
   * Eg, requesting a pad of 200,200 on an image of 250,300 will result
   * in keeping the 250,300.
   *
   * Eg2, requesting a pad of 300,300 on an image of 400,250 will result
   * in the width staying at 400 and the height padded to 300.
   *
   * @param targetWidth the size of the output canvas width
   * @param targetHeight the size of the output canvas height
   * @param color the background of the padded area.
   *
   * @return A new image that is the result of the padding
   */
  def padTo(targetWidth: Int, targetHeight: Int, color: java.awt.Color = java.awt.Color.WHITE): Image = {
    val w = if (width < targetWidth) targetWidth else width
    val h = if (height < targetHeight) targetHeight else height
    val filled = Image.filled(w, h, color)
    val x = ((w - width) / 2.0).toInt
    val y = ((h - height) / 2.0).toInt
    filled.draw(x, y, this)
    filled
  }

  /**
   * Creates a new image which is this image scaled so that it does not
   * exceed the given bounds. The resultant image will retain the aspect ratio.
   *
   * Eg, requesting a bound of 200,200 on an image of 300,600 will
   * result in a scale to 100,200.
   *
   * Eg2, requesting a bound of 150,200 on an image of 150,50 will
   * result in a scale to 35,50
   *
   * Eg3, requesting a bound of 300,300 on an image of 100,150 will
   * result in a scale to 200,300
   *
   * @param boundedWidth the maximum width
   * @param boundedHeight the maximum height
   *
   * @return A new image that is the result of the padding
   */
  def bound(boundedWidth: Int, boundedHeight: Int): Image = {
    val dimensions = ImageTools.dimensionsToFit((boundedWidth, boundedHeight), (width, height))
    scaleTo(dimensions._1, dimensions._2)
  }

  /**
   * Creates a new Image with the same dimensions of this image and with
   * all the pixels initialized to the given color
   *
   * @return a new Image with the same dimensions as this
   */
  def filled(color: Int): Image = filled(new java.awt.Color(color))

  /**
   * Creates a new Image with the same dimensions of this image and with
   * all the pixels initialized to the given color
   *
   * @return a new Image with the same dimensions as this
   */
  def filled(color: java.awt.Color): Image = Image.filled(width, height, color)

  def writer[T <: ImageWriter](format: Format[T]): T = format.writer(this)

  // This tuple contains all the state that identifies this particular image.
  private[scrimage] def imageState = (width, height, pixels.toList)

  // See this Stack Overflow question to see why this is implemented this way.
  // http://stackoverflow.com/questions/7370925/what-is-the-standard-idiom-for-implementing-equals-and-hashcode-in-scala
  override def hashCode = imageState.hashCode

  override def equals(other: Any): Boolean =
    other match {
      case that: Image => imageState == that.imageState
      case _ => false
    }

  /**
   * Creates a new Image which has the same dimensions as this image, but with all pixels set to the given
   * pixel value.
   */
  def fill(color: java.awt.Color): Image = new Image(raster.map(pixel => ARGBPixel.int2pixel(color.getRGB)))

  /**
   * Creates a MutableImage instance backed by this image.
   *
   * Note, any changes to the mutable image write back to this Image.
   * If you want a mutable copy then you must first copy this image
   * before invoking this operation.
   *
   * @return
   */
  def toMutable: MutableImage = new MutableImage(null)

  /**
   * Creates an AsyncImage instance backed by this image.
   *
   * The returned AsyncImage will contain the same backing array
   * as this image.
   *
   * To return back to an image instance use asyncImage.toImage
   *
   * @return an AsyncImage wrapping this image.
   */
  def toAsync(implicit executionContext: ExecutionContext): AsyncImage = AsyncImage(this)

  /**
   * Clears all image data to the given color
   */
  def clear(color: Color): Image = filled(color)

  def watermark(text: String): Image = watermark(text, 24, 0.5)
  def watermark(text: String, fontSize: Int, alpha: Double): Image = filter(new Watermark(text, fontSize, alpha))
}

object Image {

  ImageIO.scanForPlugins()
  val CANONICAL_DATA_TYPE = BufferedImage.TYPE_INT_ARGB
  val SCALE_THREADS = Runtime.getRuntime.availableProcessors()

  /**
   * Create a new Image from an array of pixels. The specified
   * width and height must match the number of pixels.
   *
   * @return a new Image
   */
  def apply(w: Int, h: Int, pixels: Array[Int]): Image = {
    require(w * h == pixels.size)
    Image.empty(w, h).map((x, y, p) => pixels(PixelTools.coordinateToOffset(x, y, w)))
  }

  /**
   * Create a new Image from an array of bytes. This is intended to create
   * an image from an image format eg PNG, not from raw pixels.
   *
   * @param bytes the bytes from the format stream
   * @return a new Image
   */
  def apply(bytes: Array[Byte]): Image = apply(new ByteArrayInputStream(bytes))

  def apply(in: InputStream): Image = {
    require(in != null)
    require(in.available > 0)

    val bytes = IOUtils.toByteArray(in) // lets buffer in case we have to repeat
    IOUtils.closeQuietly(in)

    try {
      apply(ImageIO.read(new ByteArrayInputStream(bytes)))
    } catch {
      case e: Exception =>
        import scala.collection.JavaConverters._
        ImageIO.getImageReaders(new ByteArrayInputStream(bytes)).asScala.foldLeft(None: Option[Image]) {
          (value, reader) => try {
            reader.setInput(new ByteArrayInputStream(bytes), true, true)
            val params = reader.getDefaultReadParam
            val imageTypes = reader.getImageTypes(0)
            while (imageTypes.hasNext) {
              val imageTypeSpecifier = imageTypes.next()
              val bufferedImageType = imageTypeSpecifier.getBufferedImageType
              if (bufferedImageType == BufferedImage.TYPE_BYTE_GRAY) {
                params.setDestinationType(imageTypeSpecifier)
              }
            }
            val bufferedImage = reader.read(0, params)
            Some(apply(bufferedImage))
          } catch {
            case e: Exception => None
              }
        }.getOrElse(throw new RuntimeException("Unparsable image"))
    }
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
      case _ => Image.empty(10, 10) // todo
    }
  }

  /**
   * Creates a new Image which is a copy of the given image.
   * Any operations to the new object do not affect the original image.
   *
   * @param image the image to copy
   *
   * @return a new Image object.
   */
  def apply(image: Image): Image = new Image(image.raster.copy)

  private[scrimage] def _empty(awt: java.awt.Image): BufferedImage = _empty(awt.getWidth(null), awt.getHeight(null))
  private[scrimage] def _empty(width: Int, height: Int): BufferedImage = new
      BufferedImage(width, height, CANONICAL_DATA_TYPE)

  /**
   * Creates a new image with the given width and height all all pixels set the given pixel value.
   */
  def filled(width: Int, height: Int, color: Int): Image = filled(width, height, new java.awt.Color(color))
  def filled(width: Int, height: Int, color: java.awt.Color): Image = {
    new Image(Raster.apply(width, height).setAll(ARGBPixel.int2pixel(color.getRGB)))
  }

  /**
   * Create a new Image that is the given width and height with no initialization. This will usually result in a
   * default black background (all pixel data defaulting to zeroes) but that is not guaranteed.
   *
   * @param width the width of the new image
   * @param height the height of the new image
   *
   * @return the new Image with the given width and height
   */
  def empty(width: Int, height: Int): Image = new Image(Raster.apply(width, height))
}

sealed trait ScaleMethod
object ScaleMethod {
  object FastScale extends ScaleMethod
  object Lanczos3 extends ScaleMethod
  object BSpline extends ScaleMethod
  object Bilinear extends ScaleMethod
  object Bicubic extends ScaleMethod
}

object Implicits {
  implicit def awt2rich(awt: java.awt.Image) = Image(awt)
}