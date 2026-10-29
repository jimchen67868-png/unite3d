package com.example.minieditor

import android.content.Context
import android.widget.ScrollView

/** A ScrollView that never grows taller than maxHeightPx (0 = no limit). */
class MaxHeightScrollView(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, h)
    }
}
