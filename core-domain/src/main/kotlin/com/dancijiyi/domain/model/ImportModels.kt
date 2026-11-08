package com.dancijiyi.domain.model

/** 导入结果状态 */
enum class ImportStatus {
    /** 正常跑完（可能含失败行） */
    DONE,

    /** 用户中途取消：已提交批次保留 */
    CANCELED,

    /** 整份文件级失败（表头错、编码错、超限等），一行未入库 */
    FAILED,
}

/** 单行失败原因（用于结果页明细与 import_record.failed_detail_json） */
data class RowFailure(val line: Int, val reason: String)

/** 一次导入的完整报告 */
data class ImportReport(
    val fileName: String,
    val format: String,
    val bookId: Long?,
    val status: ImportStatus,
    val total: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 失败明细，最多保留前 100 条（避免单行超长） */
    val failedRows: List<RowFailure> = emptyList(),
    /** 整份文件级错误信息；status=FAILED 时非空 */
    val error: String? = null,
) {
    val successCount: Int get() = inserted + updated
}

/** 导入进度（导入中回调，UI 直接渲染） */
data class ImportProgress(
    /** 已解析的行数（含失败行） */
    val processed: Int,
    /** 已读取字节数（用于按文件大小估算百分比） */
    val bytesRead: Long,
    /** 文件总字节；未知时为 null */
    val totalBytes: Long?,
    val inserted: Int,
    val failed: Int,
) {
    /** 0f..1f；文件大小未知时返回 null，UI 用不确定进度条 */
    val fraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0) return null
            return (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}
