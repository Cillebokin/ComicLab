// Adapted from joyeli/yakuyomi-engine f990b451, GPL-3.0. See translation/NOTICE.txt.
package li.joye.yakuyomi.engine

data class OcrConfig(
    val textHeight: Int = 48,         // 48px CTC
    val minTextLength: Int = 0,       // config.ocr.min_text_length
    val ignoreBubble: Int = 0,        // 〔設定〕config.ocr.ignore_bubble：1–50 開啟，跳過彩色/非氣泡 SFX 類文字（預設 0＝關）
    val minProb: Float = 0.5f,        // config.ocr.prob：OCR 平均信心 < 此值就丟（剃除低信心誤讀；m-i-t 預設 0.5）
    // OCR 裁切前把偵測 quad 四邊各外擴 N px（RotRect.expand；只動 OCR 裁切、**不動偵測框** ⇒ 去字遮罩走 seg 筆畫不受影響）。
    // 病根：偵測框太瘦把字切掉 → 48px CTC 空讀（model_48px_ctc 對 0 字元框在 prob 門檻前就丟）→ 該區被 Pipeline 的
    // textRegions filter 濾掉 → 留原文不翻＝使用者看到的「漏氣泡」。桌面 16 頁實測：pad=4 讀出 345→398(+15%)、框數不變、
    // 弄壞 9 vs 救回 350；006「その通りじゃ」框僅 23px 寬「通」被切 → pad=0 空讀、pad=12 讀對 p=0.993。
    // ★ 預設 4＝真機 A/B 定案（sandbox 6 頁 161 框、按偵測框 index 精確配對）：救回 2（含 006「その通りじゃ」＝
    //   使用者回報的漏氣泡）、**弄壞 0**、實質修復 ~14（'と一も百白です'→'とても面白いですね'、'あいませか'→
    //   'ありませんか'、'お父雄'→'お父様'、'そんな学識も'→'そんな常識も'…），代價＝微小雜訊（'！'→'ー'、少個假名，
    //   LLM 容錯；同 [useBicubic] 的權衡）。**且 OCR 快 ~20%**（框變寬→warp 後 strip 比例→CTC 序列變短）。
    //   pad=8/12 開始退步（8：弄壞 2；12：弄壞 1）⇒ 4 是甜蜜點。桌面 m-i-t warp 模擬曾給 +15% 讀出，真機只 +2
    //   （引擎自刻 bicubic warp 的 baseline 已達 98%），故真機定值不可照抄桌面。
    val stripPad: Int = 4,
    // 逐行並發 OCR：小圖塊（48px 高、窄）吃不滿 intra-op 4 緒 → 改「每行單緒、N 行並發」把核填滿。
    // concurrent=true → NCNN Net num_threads 設 1（單行單緒、逐條 forward 不進 ncnn 全域鎖）、靠 Semaphore(concurrency) 並發；
    // false → 單行用滿 NUM_THREADS、序列。純 CPU ⇒ 收益需實測（sandbox 去背比較 OCR 列 A/B）。與「批次 padding」不同：零 padding 浪費。
    val concurrent: Boolean = false,   // 預設開：真機 8.9s→4.8s(快46%)、零品質風險(每行邏輯不變)、8 核全填滿
    val concurrency: Int = 1,         // 同時在飛的行數上限（＝核數，全核並發；conc=核數為甜蜜點，再高無核可用）
    // 裁切縮放內插法：true=手刻 perspective bicubic（救小假名漏讀→句尾否定不再翻反）、false=Canvas bilinear（舊）。
    // parity 517字/100行 vs bilinear 486/96；真機 A/B 驗過：+6% OCR 時間、把「才能がある/言わないから/言いなさい」
    // 等句尾/關鍵詞讀回（消滅最陰險的「意思相反」），偶爾多讀個雜訊字（LLM 可容錯）。淨正面 → 預設開。
    val useBicubic: Boolean = true,
    // OCR strip 銳化（unsharp mask，見 Ocr.unsharp）：抵銷 warp 把 ~30px 寬直行上採樣到 48px 的模糊、
    // 救回被糊掉漏讀的小假名（v0.16.9 加）。★預設開＝實測救回小假名（對本就讀對 p≈1.0 的乾淨行無副作用）；
    // 關＝退回無銳化（strip 較糊、小假名可能漏讀）。原本硬編碼永遠開，2026-07-16 抽成設定（進階玩家可關）。
    val ocrUnsharp: Boolean = true,
    // OCR 半精度，兩段分開開：storage＝權重/中間值存 fp16（省一半記憶體頻寬）；arith＝用 fp16 指令算。
    // 真機 A/B（2026-09-21）：兩者全開快 27–32% 但 transformer 零星讀錯字、全關同 fp32 但慢 32–45%；storage-only（fp16s）
    // 是第三條路。產品解法＝下面的 ncnnMixed（backbone 吃 fp16 的速度、transformer 留 fp32 的準度）；這兩個仍是 mixed 的前提。
    val ncnnFp16Storage: Boolean = true,
    val ncnnFp16Arith: Boolean = true,
    // 混合精度（backbone fp16、transformer+char_pred fp32）：模型旁有 `<name>_mixed.ncnn.param` 就優先用它（同一份 .bin）。
    // 只在 ncnnFp16Storage 開且 CPU 有 asimdhp 時生效（否則 mixed param 裡的 Cast 會把 fp32 讀成垃圾），Ocr 會自動退回原 param。
    // 真機 9 頁 242 行：mixed 241（唯一不同那行是真值錯）、全 fp16 219 → 預設開；A/B 想固定跑「全 fp16」時關掉。
    val ncnnMixed: Boolean = true,
)
