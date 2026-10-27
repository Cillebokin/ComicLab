package li.joye.yakuyomi.engine

/** ComicLab共用ncnn，所有调用由同一翻译任务串行执行，不建立SimpleOMP常驻池。 */
data class NcnnFlavor(val numThreads: Int = 2) {
    companion object { val DEFAULT = NcnnFlavor() }
}

object NcnnBackend {
    val available: Boolean = runCatching { System.loadLibrary("comiclab_translation"); true }.getOrDefault(false)
    private external fun createNetNative(paramPath: String, binPath: String): Long
    private external fun createNetExNative(paramPath: String, binPath: String, numThreads: Int, fp16Storage: Boolean, fp16Arith: Boolean): Long
    private external fun cpuSupportsFp16Native(): Boolean
    external fun releaseNet(handle: Long)
    val cpuSupportsFp16: Boolean by lazy { available && cpuSupportsFp16Native() }
    fun createNet(param: String, bin: String): Long = createNetNative(param, bin)
    fun createNet(param: String, bin: String, flavor: NcnnFlavor): Long = createNetEx(param, bin, flavor.numThreads, true, true)
    fun createNetEx(param: String, bin: String, threads: Int, storage: Boolean, arithmetic: Boolean): Long =
        createNetExNative(param, bin, threads, storage, arithmetic)
    private external fun detectDbnetNative(handle: Long, input: FloatArray, w: Int, h: Int, db: FloatArray, mask: FloatArray): Int
    private external fun ocrCtcNative(handle: Long, input: FloatArray, w: Int, h: Int, pe: FloatArray, t: Int, indices: IntArray, logp: FloatArray): Int
    private external fun inpaintAotNative(handle: Long, image: FloatArray, mask: FloatArray, size: Int, output: FloatArray): Int
    fun detectDbnet(handle: Long, input: FloatArray, w: Int, h: Int, db: FloatArray, mask: FloatArray, flavor: NcnnFlavor = NcnnFlavor.DEFAULT): Int =
        detectDbnetNative(handle, input, w, h, db, mask)
    fun ocrCtc(handle: Long, input: FloatArray, w: Int, h: Int, pe: FloatArray, t: Int, indices: IntArray, logp: FloatArray, serialize: Boolean): Int =
        ocrCtcNative(handle, input, w, h, pe, t, indices, logp)
    fun inpaintAot(handle: Long, image: FloatArray, mask: FloatArray, size: Int, output: FloatArray): Int =
        inpaintAotNative(handle, image, mask, size, output)
}
