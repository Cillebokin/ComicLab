package com.example.comiclab.core.reader.enhance

import android.content.res.AssetManager
import android.graphics.Bitmap

/** 所有调用限于同一工作线程；取消在分块之间检查，不并发销毁native模型 */
internal object NativeSuperResolution {
    init { System.loadLibrary("comiclab_superresolution") }
    external fun create(assets: AssetManager, gpu: Boolean, threads: Int, scale: Int = 2): Long
    external fun infer(handle: Long, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): ByteArray
    external fun release(handle: Long)
}
