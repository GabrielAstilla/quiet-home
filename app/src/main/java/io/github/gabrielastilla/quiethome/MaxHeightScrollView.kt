package io.github.gabrielastilla.quiethome

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** ScrollView that wraps its content up to [maxHeight] px, then scrolls. Lets short sections centre vertically. */
class MaxHeightScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    var maxHeight = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight else minOf(maxHeight, avail)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}
