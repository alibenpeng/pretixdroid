import android.util.Log

val printHelper = PrintHelper()

class PrintHelper

private val maxPrintLineLength = 20

/**
 * Split lines that are too long to be printed on spaces or hyphens and
 * return an `Array` of adequately shortened lines
 */

fun splitLongLines(longLine: String): Array<String> {
    val lineSegments = longLine.split(Regex("(?<=[- ])|(?=[- ])"))

    Log.d("splitLongLines", "Line Segments: " + lineSegments.toString())

    var length = 0
    var shortLine = ""
    var outArray: Array<String> = emptyArray()

    for (i in 0..lineSegments.lastIndex) {
        when {

        // Ignore whitespace that would appear at the beginning or end of a shortLine
            (lineSegments[i] == " ") -> {
                when {
                    (((length + lineSegments[i].length) <= maxPrintLineLength) and
                            ((length + lineSegments[i].length + lineSegments[i+1].length) > maxPrintLineLength)) -> {}

                    ((length + lineSegments[i].length) > maxPrintLineLength) -> {}
                    else -> {
                        shortLine += lineSegments[i]
                        length += lineSegments[i].length
                    }
                }
            }

        // If shortLine hasn't reached its max length yet, append the next segment
        // Always append hyphens, no matter what!
            (((length + lineSegments[i].length) <= maxPrintLineLength) or (lineSegments[i] == "-")) -> {
                Log.d("splitLongLines", "appending to shortLine: ${lineSegments[i]}")
                shortLine += lineSegments[i]
                length += lineSegments[i].length
            }

        // Append shortLine to output Array
            else -> {
                Log.d("splitLongLines", "appending to outArray: $shortLine")
                outArray += shortLine
                shortLine = lineSegments[i]
                length = lineSegments[i].length
            }
        }
    }
    outArray += shortLine
    return outArray
}

/**
 * Build a `String` the printer understands.
 *
 * Printer configuration and page layout happen here.
 * Any character set conversion happens at a later stage
 * in the function that actually sends the data over the air.
 */

fun buildUartPrinterString(name:String, sat:String, orderCode:String) : String {

    // Build a map from the args to iterate through
    val args = mapOf(Pair("name", name), Pair("sat", sat), Pair("orderCode", orderCode))

    // Printer setup commands
    val prnInitCmd        = "@\r\n"    // Set speed to 5 ips
    val prnSpeedCmd        = "SS3\r\n"    // Set speed to 5 ips
    val prnDensityCmd      = "SD20\r\n"   // Set density to 20
    val prnLabelWidthCmd   = "SW800\r\n"  // Set label width to 800
    val prnOriCmd          = "SOT\r\n"    // Set printing direction from top to bottom
    val prnCharSet         = "CS2,6\r\n" // Set german charset (2) and Latin1+Euro code page (22)

    // Print command - this has to be the last command, on its own on a single line!
    val prnPrintCmd = "P1\r\n" // 1 = Print *one* label

    // Origin of the printer is the upper left corner
    val prnXpos        = 400  // P1: X position (center of the ticket)
    var prnYpos        = 10   // P2: Y position (initial)
    val prnFontSel     = "U"  // P3: Font Selection ("U" = ASCII, all other options are for asian character sets)
    val prnFontWidth   = 65   // P4: Font Width (dot)
    val prnFontHeight  = 65   // P5: Font Height (dot)
    val prnRCSpacing   = "+1" // P6: Right-side Character Spacing (dot), +/- can be used
    var prnBold        = "B"  // P7: Bold Printing: valid values are "N" (normal) and "B" (bold)
    val prnReverse     = "N"  // P8: Reverse Printing: valid values are "N" (normal) and "R" (reversed)
    val prnStyle       = "N"  // P9: Text Style: valid values are "N" (normal) and "I" (italic)
    val prnRotate      = 0    // P10: Rotation: valid values are 0-3 (0, 90, 180, 270 degrees)
    val prnAlignment   = "C"  // P11: Text Alignment: valid values are "L" (left), "R" (right) and "C" (center)
    val prnDirection   = 0    // P12: Text Direction: valid values are 0 (left to right) and 1 (right to left)

    // Assemble the printer configuration commands
    var printString = String.format("%s%s%s%s%s%s", prnInitCmd, prnSpeedCmd, prnDensityCmd, prnLabelWidthCmd, prnOriCmd, prnCharSet)

    for ((k, v) in args) {
        var currentLine: String
        if (k != "name") prnBold = "N" // Print only names in bold font


        if (v.length >= maxPrintLineLength) {
            Log.w("BLE PRINT", "$k length: " + v.length)
            splitLongLines(v).forEach {
                // Print parameters          1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,'data'
                currentLine = String.format("V%d,%d,%s,%d,%d,%s,%s,%s,%s,%d,%s,%d,'%s'\r\n",
                        prnXpos, prnYpos, prnFontSel, prnFontWidth, prnFontHeight,
                        prnRCSpacing, prnBold, prnReverse, prnStyle, prnRotate,
                        prnAlignment, prnDirection, it)
                prnYpos += prnFontHeight // Line feed
                printString += currentLine

            }
            prnYpos += prnFontHeight // Line feed

        } else {
            // Print parameters          1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,'data'
            currentLine = String.format("V%d,%d,%s,%d,%d,%s,%s,%s,%s,%d,%s,%d,'%s'\r\n",
                    prnXpos, prnYpos, prnFontSel, prnFontWidth, prnFontHeight,
                    prnRCSpacing, prnBold, prnReverse, prnStyle, prnRotate,
                    prnAlignment, prnDirection, v)
            prnYpos += prnFontHeight * 2 // Line feed
            printString += currentLine
        }

    }
    printString += prnPrintCmd
    Log.d("UARTprint", "printString: $printString")
    Log.d("UARTprint", "prnYpos: $prnYpos")
    return printString
}

