package com.kannadadoc.export

import com.kannadadoc.parser.ParsedDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubWriter(
    private val htmlZipWriter: HtmlZipWriter = HtmlZipWriter()
) {
    fun write(doc: ParsedDocument, outputStream: OutputStream) {
        val title = doc.fileName.substringBeforeLast('.').ifBlank { "Kadata Document" }
        val bookUid = "urn:uuid:${UUID.randomUUID()}"
        val modifiedIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

        val contentOpf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId" xml:lang="kn">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="BookId">$bookUid</dc:identifier>
    <dc:title>${esc(title)}</dc:title>
    <dc:language>kn</dc:language>
    <dc:creator>Kadata</dc:creator>
    <meta property="dcterms:modified">$modifiedIso</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
  </spine>
</package>
"""

        val navXhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="kn">
<head>
  <meta charset="UTF-8"/>
  <title>${esc(title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>${esc(title)}</h1>
    <ol>
      <li><a href="chapter1.xhtml">${esc(title)}</a></li>
    </ol>
  </nav>
</body>
</html>
"""

        val tocNcx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$bookUid"/>
    <meta name="dtb:depth" content="1"/>
  </head>
  <docTitle><text>${esc(title)}</text></docTitle>
  <navMap>
    <navPoint id="navPoint-1" playOrder="1">
      <navLabel><text>${esc(title)}</text></navLabel>
      <content src="chapter1.xhtml"/>
    </navPoint>
  </navMap>
</ncx>
"""

        val styleCss = """body {
  font-family: 'Noto Sans Kannada', 'Tunga', serif;
  line-height: 1.75;
  margin: 1em;
}
table { width: 100%; border-collapse: collapse; margin: 1em 0; }
td, th { border: 1px solid #999; padding: 6px 8px; vertical-align: top; }
"""

        val chapter1Xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="kn">
<head>
  <meta charset="UTF-8"/>
  <title>${esc(title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
${htmlZipWriter.renderBodyHtml(doc, xhtmlMode = true)}
</body>
</html>
"""

        ZipOutputStream(outputStream).use { zos ->
            // EPUB spec requires uncompressed `mimetype` as the very first entry
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = CRC32().apply { update(mimeBytes) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            putUtf8Entry(zos, "META-INF/container.xml", containerXml)
            putUtf8Entry(zos, "OEBPS/content.opf", contentOpf)
            putUtf8Entry(zos, "OEBPS/nav.xhtml", navXhtml)
            putUtf8Entry(zos, "OEBPS/toc.ncx", tocNcx)
            putUtf8Entry(zos, "OEBPS/style.css", styleCss)
            putUtf8Entry(zos, "OEBPS/chapter1.xhtml", chapter1Xhtml)
        }
    }

    private fun putUtf8Entry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun esc(s: String): String = XmlSanitizer.escapeXml(s)
}
