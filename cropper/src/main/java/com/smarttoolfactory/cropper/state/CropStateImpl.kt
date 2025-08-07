package com.smarttoolfactory.cropper.state

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
import com.smarttoolfactory.cropper.TouchRegion
import com.smarttoolfactory.cropper.model.AspectRatio
import com.smarttoolfactory.cropper.model.CropData
import com.smarttoolfactory.cropper.settings.CropProperties

internal const val MIN_OVERLAY_RATIO = 0.5F

val CropState.cropData: CropData
    get() = CropData(
        zoom = animatableZoom.targetValue,
        pan = Offset(animatablePanX.targetValue, animatablePanY.targetValue),
        rotation = animatableRotation.targetValue,
        overlayRect = overlayRect,
        cropRect = cropRect
    )

/**
 * Base class for crop operations. Any class that extends this class gets access to pan, zoom,
 * rotation values and animations via [TransformState], fling and moving back to bounds animations.
 * @param imageSize size of the **Bitmap**
 * @param containerSize size of the Composable that draws **Bitmap**. This is full size
 * of the Composable. [drawAreaSize] can be smaller than [containerSize] initially based
 * on content scale of Image composable
 * @param drawAreaSize size of the area that **Bitmap** is drawn
 * @param maxZoom maximum zoom value
 * @param fling when set to true dragging pointer builds up velocity. When last
 * pointer leaves Composable a movement invoked against friction till velocity drops below
 * to threshold
 * @param zoomable when set to true zoom is enabled
 * @param pannable when set to true pan is enabled
 * @param rotatable when set to true rotation is enabled
 * @param limitPan limits pan to bounds of parent Composable. Using this flag prevents creating
 * empty space on sides or edges of parent
 */
abstract class CropState internal constructor(
    imageSize: IntSize,
    containerSize: IntSize,
    drawAreaSize: IntSize,
    maxZoom: Float,
    internal var fling: Boolean = true,
    internal var aspectRatio: AspectRatio,
    internal var overlayRatio: Float,
    zoomable: Boolean = true,
    pannable: Boolean = true,
    rotatable: Boolean = false,
    limitPan: Boolean = false
) : TransformState(
    imageSize = imageSize,
    containerSize = containerSize,
    drawAreaSize = drawAreaSize,
    initialZoom = 1f,
    initialRotation = 0f,
    maxZoom = maxZoom,
    zoomable = zoomable,
    pannable = pannable,
    rotatable = rotatable,
    limitPan = limitPan
) {

    private val animatableRectOverlay = Animatable(
        getOverlayFromAspectRatio(
            containerSize.width.toFloat(),
            containerSize.height.toFloat(),
            drawAreaSize.width.toFloat(),
            aspectRatio,
            overlayRatio
        ),
        Rect.VectorConverter
    )

    val overlayRect: Rect
        get() = animatableRectOverlay.value

    var cropRect: Rect = Rect.Zero
        get() = getCropRectangle(
            imageSize.width,
            imageSize.height,
            drawAreaRect,
            animatableRectOverlay.targetValue
        )
        private set


    private var initialized: Boolean = false

    /**
     * Region of touch inside, corners of or outside of overlay rectangle
     */
    var touchRegion by mutableStateOf(TouchRegion.None)

    internal suspend fun init() {
        // When initial aspect ratio doesn't match drawable area
        // overlay gets updated so updates draw area as well
        animateTransformationToOverlayBounds(overlayRect, animate = true)
        initialized = true
    }

    /**
     * Update properties of [CropState] and animate to valid intervals if required
     */
    internal open suspend fun updateProperties(
        cropProperties: CropProperties,
        forceUpdate: Boolean = false
    ) {

        if (!initialized) return

        fling = cropProperties.fling
        pannable = cropProperties.pannable
        zoomable = cropProperties.zoomable
        rotatable = cropProperties.rotatable

        val maxZoom = cropProperties.maxZoom

        // Update overlay rectangle
        val aspectRatio = cropProperties.aspectRatio

        // Ratio of overlay to screen
        val overlayRatio = cropProperties.overlayRatio

        if (
            this.aspectRatio.value != aspectRatio.value ||
            maxZoom != zoomMax ||
            this.overlayRatio != overlayRatio ||
            forceUpdate
        ) {
            this.aspectRatio = aspectRatio
            this.overlayRatio = overlayRatio

            zoomMax = maxZoom
            animatableZoom.updateBounds(zoomMin, zoomMax)

            val currentZoom = if (zoom > zoomMax) zoomMax else zoom

            // Set new zoom
            snapZoomTo(currentZoom)

            // Calculate new region of image is drawn. It can be drawn left of 0 and right
            // of container width depending on transformation
            drawAreaRect = updateImageDrawRectFromTransformation()

            // Update overlay rectangle based on current draw area and new aspect ratio
            animateOverlayRectTo(
                getOverlayFromAspectRatio(
                    containerSize.width.toFloat(),
                    containerSize.height.toFloat(),
                    drawAreaSize.width.toFloat(),
                    aspectRatio,
                    overlayRatio
                )
            )
        }

        // Animate zoom, pan, rotation to move draw area to cover overlay rect
        // inside draw area rect
        animateTransformationToOverlayBounds(overlayRect, animate = true)
    }

    /**
     * Animate overlay rectangle to target value
     */
    internal suspend fun animateOverlayRectTo(
        rect: Rect,
        animationSpec: AnimationSpec<Rect> = tween(400)
    ) {
        animatableRectOverlay.animateTo(
            targetValue = rect,
            animationSpec = animationSpec
        )
    }

    /**
     * Snap overlay rectangle to target value
     */
    internal suspend fun snapOverlayRectTo(rect: Rect) {
        animatableRectOverlay.snapTo(rect)
    }

    /**
     * Set rotation programmatically without affecting other states
     * This method allows external control of rotation without triggering recomposition
     */
     suspend fun setRotation(rotation: Float, animate: Boolean = true) {
        val normalizedRotation = rotation % 360f
        if (animate) {
            animateRotationTo(normalizedRotation)
        } else {
            snapRotationTo(normalizedRotation)
        }
        
        // Update draw area rect after rotation change
        drawAreaRect = updateImageDrawRectFromTransformation()
    }

    /**
     * Get current rotation value
     */
    internal fun getCurrentRotation(): Float {
        return animatableRotation.targetValue
    }

    /**
     * Set pan programmatically without affecting other states
     */
    internal suspend fun setPan(pan: Offset, animate: Boolean = true) {
        if (animate) {
            animatePanXto(pan.x)
            animatePanYto(pan.y)
        } else {
            snapPanXto(pan.x)
            snapPanYto(pan.y)
        }
        
        // Update draw area rect after pan change
        drawAreaRect = updateImageDrawRectFromTransformation()
    }

    /**
     * Set zoom programmatically without affecting other states
     */
     suspend fun setZoom(zoom: Float, animate: Boolean = true) {
        val clampedZoom = zoom.coerceIn(zoomMin, zoomMax)
        if (animate) {
            animateZoomTo(clampedZoom)
        } else {
            snapZoomTo(clampedZoom)
        }
        
        // Update draw area rect after zoom change
        drawAreaRect = updateImageDrawRectFromTransformation()
    }

    /**
     * Set all transformations programmatically
     */
    internal suspend fun setTransformations(
        pan: Offset? = null,
        zoom: Float? = null,
        rotation: Float? = null,
        animate: Boolean = true
    ) {
        if (pan != null) {
            if (animate) {
                animatePanXto(pan.x)
                animatePanYto(pan.y)
            } else {
                snapPanXto(pan.x)
                snapPanYto(pan.y)
            }
        }
        
        if (zoom != null) {
            val clampedZoom = zoom.coerceIn(zoomMin, zoomMax)
            if (animate) {
                animateZoomTo(clampedZoom)
            } else {
                snapZoomTo(clampedZoom)
            }
        }
        
        if (rotation != null) {
            val normalizedRotation = rotation % 360f
            if (animate) {
                animateRotationTo(normalizedRotation)
            } else {
                snapRotationTo(normalizedRotation)
            }
        }
        
        // Update draw area rect after all changes
        drawAreaRect = updateImageDrawRectFromTransformation()
    }

    /*
        Touch gestures
     */
    internal abstract suspend fun onDown(change: PointerInputChange)

    internal abstract suspend fun onMove(changes: List<PointerInputChange>)

    internal abstract suspend fun onUp(change: PointerInputChange)

    /*
        Transform gestures
     */
    internal abstract suspend fun onGesture(
        centroid: Offset,
        panChange: Offset,
        zoomChange: Float,
        rotationChange: Float,
        mainPointer: PointerInputChange,
        changes: List<PointerInputChange>
    )

    internal abstract suspend fun onGestureStart()

    internal abstract suspend fun onGestureEnd(onBoundsCalculated: () -> Unit)

    // Double Tap
    internal abstract suspend fun onDoubleTap(
        offset: Offset,
        zoom: Float = 1f,
        onAnimationEnd: () -> Unit
    )

    /**
     * Check if area that image is drawn covers [overlayRect]
     */
    internal fun isOverlayInImageDrawBounds(): Boolean {
        return drawAreaRect.left <= overlayRect.left &&
                drawAreaRect.top <= overlayRect.top &&
                drawAreaRect.right >= overlayRect.right &&
                drawAreaRect.bottom >= overlayRect.bottom
    }

    /**
     * Check if [rect] is inside container bounds
     */
    internal fun isRectInContainerBounds(rect: Rect): Boolean {
        return rect.left >= 0 &&
                rect.right <= containerSize.width &&
                rect.top >= 0 &&
                rect.bottom <= containerSize.height
    }

    /**
     * Update rectangle for area that image is drawn. This rect changes when zoom and
     * pan changes and position of image changes on screen as result of transformation.
     *
     * This function is called
     *
     * * when [onGesture] is called to update rect when zoom or pan changes
     *  and if [fling] is true just after **fling** gesture starts with target
     *  value in  [StaticCropState].
     *
     *  * when [updateProperties] is called in [CropState]
     *
     *  * when [onUp] is called in [DynamicCropState] to match [overlayRect] that could be
     *  changed and animated if it's out of [containerSize] bounds or its grow
     *  bigger than previous size
     */
    internal fun updateImageDrawRectFromTransformation(): Rect {
        val containerWidth = containerSize.width
        val containerHeight = containerSize.height

        val originalDrawWidth = drawAreaSize.width
        val originalDrawHeight = drawAreaSize.height

        val panX = animatablePanX.targetValue
        val panY = animatablePanY.targetValue
        val zoom = animatableZoom.targetValue
        val rotation = animatableRotation.targetValue

        // Calculate rotated dimensions
        val rotatedWidth = if (rotation % 180 == 0f) {
            originalDrawWidth * zoom
        } else {
            originalDrawHeight * zoom
        }
        
        val rotatedHeight = if (rotation % 180 == 0f) {
            originalDrawHeight * zoom
        } else {
            originalDrawWidth * zoom
        }

        // Calculate center position
        val centerX = containerWidth / 2f
        val centerY = containerHeight / 2f

        // Calculate offset considering rotation
        val offsetX = centerX - rotatedWidth / 2f + panX
        val offsetY = centerY - rotatedHeight / 2f + panY

        return Rect(
            offset = Offset(offsetX, offsetY),
            size = Size(rotatedWidth, rotatedHeight)
        )
    }

    /**
     * Resets to bounds with animation and resets tracking for fling animation.
     * Changes pan, zoom and rotation to valid bounds based on [drawAreaRect] and [overlayRect]
     */
    internal suspend fun animateTransformationToOverlayBounds(
        overlayRect: Rect,
        animate: Boolean,
        animationSpec: AnimationSpec<Float> = tween(400)
    ) {
        val zoom = zoom.coerceAtLeast(MIN_OVERLAY_RATIO)
        val rotation = animatableRotation.targetValue

        // Calculate new pan based on overlay
        val newDrawAreaRect = calculateValidImageDrawRect(overlayRect, drawAreaRect)

        val newZoom =
            calculateNewZoom(oldRect = drawAreaRect, newRect = newDrawAreaRect, zoom = zoom)

        val leftChange = newDrawAreaRect.left - drawAreaRect.left
        val topChange = newDrawAreaRect.top - drawAreaRect.top

        val widthChange = newDrawAreaRect.width - drawAreaRect.width
        val heightChange = newDrawAreaRect.height - drawAreaRect.height

        // Calculate pan changes based on the difference in draw area position and size
        // This ensures proper pan adjustment regardless of rotation
        val panXChange = leftChange + widthChange / 2
        val panYChange = topChange + heightChange / 2

        val newPanX = pan.x + panXChange
        val newPanY = pan.y + panYChange

        if (animate) {
            resetWithAnimation(
                pan = Offset(newPanX, newPanY),
                zoom = newZoom,
                rotation = this.rotation,
                animationSpec = animationSpec
            )
        } else {
            snapPanXto(newPanX)
            snapPanYto(newPanY)
            snapZoomTo(newZoom)
            snapRotationTo(this.rotation)
        }

        resetTracking()

        drawAreaRect = updateImageDrawRectFromTransformation()
    }

    /**
     * If new overlay is bigger, when crop type is dynamic, we need to increase zoom at least
     * size of bigger dimension for image draw area([drawAreaRect]) to cover overlay([overlayRect])
     */
    private fun calculateNewZoom(oldRect: Rect, newRect: Rect, zoom: Float): Float {

        if (oldRect.size == Size.Zero || newRect.size == Size.Zero) return zoom

        val widthChange = (newRect.width / oldRect.width)
            .coerceAtLeast(1f)
        val heightChange = (newRect.height / oldRect.height)
            .coerceAtLeast(1f)

        return widthChange.coerceAtLeast(heightChange) * zoom
    }

    /**
     * Calculate valid position for image draw rectangle when pointer is up. Overlay rectangle
     * should fit inside draw image rectangle to have valid bounds when calculation is completed.
     *
     * @param rectOverlay rectangle of overlay that is used for cropping
     * @param rectDrawArea rectangle of image that is being drawn
     */
    private fun calculateValidImageDrawRect(rectOverlay: Rect, rectDrawArea: Rect): Rect {
        val rotation = animatableRotation.targetValue

        // For rotated images, we need to ensure the draw area covers the overlay
        // regardless of rotation. The overlay dimensions should be considered
        // in the context of the rotated image.
        var width = rectDrawArea.width
        var height = rectDrawArea.height

        // Ensure draw area is large enough to contain the overlay
        if (width < rectOverlay.width) {
            width = rectOverlay.width
        }

        if (height < rectOverlay.height) {
            height = rectOverlay.height
        }

        var rectImageArea = Rect(offset = rectDrawArea.topLeft, size = Size(width, height))

        // Ensure the draw area covers the overlay completely
        if (rectImageArea.left > rectOverlay.left) {
            rectImageArea = rectImageArea.translate(rectOverlay.left - rectImageArea.left, 0f)
        }

        if (rectImageArea.right < rectOverlay.right) {
            rectImageArea = rectImageArea.translate(rectOverlay.right - rectImageArea.right, 0f)
        }

        if (rectImageArea.top > rectOverlay.top) {
            rectImageArea = rectImageArea.translate(0f, rectOverlay.top - rectImageArea.top)
        }

        if (rectImageArea.bottom < rectOverlay.bottom) {
            rectImageArea = rectImageArea.translate(0f, rectOverlay.bottom - rectImageArea.bottom)
        }

        return rectImageArea
    }

    /**
     * Calculate effective aspect ratio based on current rotation
     */
    private fun getEffectiveAspectRatio(baseAspectRatio: Float): Float {
        val rotation = animatableRotation.targetValue
        return if (rotation % 180 == 0f) {
            baseAspectRatio
        } else {
            1f / baseAspectRatio
        }
    }

    /**
     * Create [Rect] to draw overlay based on selected aspect ratio
     */
    internal fun getOverlayFromAspectRatio(
        containerWidth: Float,
        containerHeight: Float,
        drawAreaWidth: Float,
        aspectRatio: AspectRatio,
        coefficient: Float
    ): Rect {

        if (aspectRatio == AspectRatio.Original) {
            val imageAspectRatio = imageSize.width.toFloat() / imageSize.height.toFloat()

            // Adjust aspect ratio calculation based on rotation
            val effectiveAspectRatio = getEffectiveAspectRatio(imageAspectRatio)

            // Maximum width and height overlay rectangle can be measured with
            val overlayWidthMax = drawAreaWidth.coerceAtMost(containerWidth * coefficient)
            val overlayHeightMax =
                (overlayWidthMax / effectiveAspectRatio).coerceAtMost(containerHeight * coefficient)

            val offsetX = (containerWidth - overlayWidthMax) / 2f
            val offsetY = (containerHeight - overlayHeightMax) / 2f

            return Rect(
                offset = Offset(offsetX, offsetY),
                size = Size(overlayWidthMax, overlayHeightMax)
            )
        }

        val overlayWidthMax = containerWidth * coefficient
        val overlayHeightMax = containerHeight * coefficient

        val aspectRatioValue = aspectRatio.value

        // Adjust aspect ratio calculation based on rotation
        val effectiveAspectRatio = getEffectiveAspectRatio(aspectRatioValue)

        var width = overlayWidthMax
        var height = overlayWidthMax / effectiveAspectRatio

        if (height > overlayHeightMax) {
            height = overlayHeightMax
            width = height * effectiveAspectRatio
        }

        val offsetX = (containerWidth - width) / 2f
        val offsetY = (containerHeight - height) / 2f

        return Rect(offset = Offset(offsetX, offsetY), size = Size(width, height))
    }

    /**
     * Get crop rectangle
     */
    private fun getCropRectangle(
        bitmapWidth: Int,
        bitmapHeight: Int,
        drawAreaRect: Rect,
        overlayRect: Rect
    ): Rect {

        if (drawAreaRect == Rect.Zero || overlayRect == Rect.Zero) return Rect(
            offset = Offset.Zero,
            Size(bitmapWidth.toFloat(), bitmapHeight.toFloat())
        )

        // Calculate latest image draw area based on overlay position
        // This is valid rectangle that contains crop area inside overlay
        val newRect = calculateValidImageDrawRect(overlayRect, drawAreaRect)

        val rotation = animatableRotation.targetValue

        // Calculate crop rect considering rotation
        return if (rotation % 180 == 0f) {
            // No dimension swap needed for 0° and 180°
            calculateCropRectNormal(newRect, overlayRect, bitmapWidth, bitmapHeight)
        } else {
            // Dimensions are swapped for 90° and 270°
            calculateCropRectRotated(newRect, overlayRect, bitmapWidth, bitmapHeight)
        }
    }

    /**
     * Calculate crop rectangle for normal orientation (0° or 180°)
     */
    private fun calculateCropRectNormal(
        drawRect: Rect,
        overlayRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Rect {
        val overlayWidth = overlayRect.width
        val overlayHeight = overlayRect.height

        val drawAreaWidth = drawRect.width
        val drawAreaHeight = drawRect.height

        val widthRatio = overlayWidth / drawAreaWidth
        val heightRatio = overlayHeight / drawAreaHeight

        val diffLeft = overlayRect.left - drawRect.left
        val diffTop = overlayRect.top - drawRect.top

        val croppedBitmapLeft = (diffLeft * (bitmapWidth / drawAreaWidth))
        val croppedBitmapTop = (diffTop * (bitmapHeight / drawAreaHeight))

        val croppedBitmapWidth = bitmapWidth * widthRatio
        val croppedBitmapHeight = bitmapHeight * heightRatio

        return Rect(
            offset = Offset(croppedBitmapLeft, croppedBitmapTop),
            size = Size(croppedBitmapWidth, croppedBitmapHeight)
        )
    }

    /**
     * Calculate crop rectangle for rotated orientation (90° or 270°)
     */
    private fun calculateCropRectRotated(
        drawRect: Rect,
        overlayRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Rect {
        // For rotated images, we need to calculate the crop rectangle differently
        // The overlay represents the visible area in the rotated coordinate system
        
        val overlayWidth = overlayRect.width
        val overlayHeight = overlayRect.height

        val drawAreaWidth = drawRect.width
        val drawAreaHeight = drawRect.height

        val widthRatio = overlayWidth / drawAreaWidth
        val heightRatio = overlayHeight / drawAreaHeight

        val diffLeft = overlayRect.left - drawRect.left
        val diffTop = overlayRect.top - drawRect.top

        // For rotated images, the crop coordinates need to be adjusted
        // The overlay position in the rotated view corresponds to different
        // positions in the original bitmap
        val croppedBitmapLeft = (diffLeft * (bitmapWidth / drawAreaWidth))
        val croppedBitmapTop = (diffTop * (bitmapHeight / drawAreaHeight))

        // For rotated images, the dimensions are swapped in the final crop
        val croppedBitmapWidth = bitmapHeight * heightRatio
        val croppedBitmapHeight = bitmapWidth * widthRatio

        return Rect(
            offset = Offset(croppedBitmapLeft, croppedBitmapTop),
            size = Size(croppedBitmapWidth, croppedBitmapHeight)
        )
    }
}

/**
 * Extension functions for easy external control of crop state transformations
 */

/**
 * Set rotation programmatically based on crop type
 */
suspend fun CropState.setRotation(rotation: Float, animate: Boolean = true) {
    when (this) {
        is DynamicCropState -> setRotationDynamic(rotation, animate)
        is StaticCropState -> setRotationStatic(rotation, animate)
        else -> setRotation(rotation, animate)
    }
}

/**
 * Set pan programmatically based on crop type
 */
suspend fun CropState.setPan(pan: Offset, animate: Boolean = true) {
    when (this) {
        is DynamicCropState -> setPanDynamic(pan, animate)
        is StaticCropState -> setPanStatic(pan, animate)
        else -> setPan(pan, animate)
    }
}

/**
 * Set zoom programmatically based on crop type
 */
suspend fun CropState.setZoom(zoom: Float, animate: Boolean = true) {
    when (this) {
        is DynamicCropState -> setZoomDynamic(zoom, animate)
        is StaticCropState -> setZoomStatic(zoom, animate)
        else -> setZoom(zoom, animate)
    }
}

/**
 * Set all transformations programmatically based on crop type
 */
suspend fun CropState.setTransformations(
    pan: Offset? = null,
    zoom: Float? = null,
    rotation: Float? = null,
    animate: Boolean = true
) {
    when (this) {
        is DynamicCropState -> setTransformationsDynamic(pan, zoom, rotation, animate)
        is StaticCropState -> setTransformationsStatic(pan, zoom, rotation, animate)
        else -> setTransformations(pan, zoom, rotation, animate)
    }
}

/**
 * Get current rotation value
 */
fun CropState.getCurrentRotation(): Float {
    return getCurrentRotation()
}
