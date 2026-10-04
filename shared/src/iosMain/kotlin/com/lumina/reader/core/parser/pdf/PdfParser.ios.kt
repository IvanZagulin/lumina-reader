@file:OptIn(ExperimentalForeignApi::class)

package com.lumina.reader.core.parser.pdf

import com.lumina.reader.core.parser.pdf.PdfParser.Companion.MAX_COVER_SIDE
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import okio.Path
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFURLCreateWithFileSystemPath
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGPDFDocumentCreateWithURL
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentIsUnlocked
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.CGPDFPageGetDrawingTransform
import platform.CoreGraphics.CGPDFPageGetRotationAngle
import platform.CoreGraphics.CGPDFPageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * CoreGraphics: the page count of the document and its first page drawn on
 * white into a JPEG, scaled like Android's PdfRenderer cover (at most 1.5x and
 * [MAX_COVER_SIDE] pixels a side). A document locked by a password counts as
 * unreadable, as PdfRenderer refuses it.
 */
internal actual fun inspectPdf(path: Path): PdfSummary? {
    val document = openDocument(path.toString()) ?: return null
    try {
        if (!CGPDFDocumentIsUnlocked(document)) return null
        val pageCount = CGPDFDocumentGetNumberOfPages(document).toInt()
        val cover = if (pageCount > 0) {
            // Pages are numbered from 1; the page belongs to the document.
            CGPDFDocumentGetPage(document, 1u)?.let(::renderCover)
        } else {
            null
        }
        return PdfSummary(pageCount, cover)
    } finally {
        CGPDFDocumentRelease(document)
    }
}

private fun openDocument(path: String) = run {
    val cfPath = CFStringCreateWithCString(null, path, kCFStringEncodingUTF8) ?: return@run null
    try {
        val url = CFURLCreateWithFileSystemPath(null, cfPath, kCFURLPOSIXPathStyle, false) ?: return@run null
        try {
            CGPDFDocumentCreateWithURL(url)
        } finally {
            CFRelease(url)
        }
    } finally {
        CFRelease(cfPath)
    }
}

private fun renderCover(page: CGPDFPageRef): ByteArray? {
    var pageWidth = 1.0
    var pageHeight = 1.0
    CGPDFPageGetBoxRect(page, kCGPDFMediaBox).useContents {
        pageWidth = size.width.coerceAtLeast(1.0)
        pageHeight = size.height.coerceAtLeast(1.0)
    }
    // A page turned by a quarter is shown with its sides swapped.
    if (CGPDFPageGetRotationAngle(page) % 180 != 0) {
        val swap = pageWidth
        pageWidth = pageHeight
        pageHeight = swap
    }
    val scale = minOf(1.5, MAX_COVER_SIDE / maxOf(pageWidth, pageHeight))
    val width = (pageWidth * scale).toInt().coerceIn(1, MAX_COVER_SIDE).toDouble()
    val height = (pageHeight * scale).toInt().coerceIn(1, MAX_COVER_SIDE).toDouble()

    val format = UIGraphicsImageRendererFormat()
    format.scale = 1.0
    format.opaque = true
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(width, height), format = format)
    val image = renderer.imageWithActions { context ->
        val cg = context?.CGContext ?: return@imageWithActions
        CGContextSetRGBFillColor(cg, 1.0, 1.0, 1.0, 1.0)
        CGContextFillRect(cg, CGRectMake(0.0, 0.0, width, height))
        // UIKit's origin is the top-left corner, PDF's the bottom-left one.
        CGContextTranslateCTM(cg, 0.0, height)
        CGContextScaleCTM(cg, width / pageWidth, -height / pageHeight)
        // Rotation and the box origin; the target is the page's own size, so
        // this transform does not scale (it never scales up).
        val transform = CGPDFPageGetDrawingTransform(page, kCGPDFMediaBox, CGRectMake(0.0, 0.0, pageWidth, pageHeight), 0, true)
        CGContextConcatCTM(cg, transform)
        CGContextDrawPDFPage(cg, page)
    }
    return UIImageJPEGRepresentation(image, 0.85)?.toByteArray()
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) {
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return result
}
