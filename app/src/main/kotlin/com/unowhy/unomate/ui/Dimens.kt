package com.unowhy.unomate.ui

import android.content.Context
import android.view.View
import androidx.fragment.app.Fragment

/** Pixels pour `value` dp. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun View.dp(value: Int): Int = context.dp(value)

fun Fragment.dp(value: Int): Int = requireContext().dp(value)
