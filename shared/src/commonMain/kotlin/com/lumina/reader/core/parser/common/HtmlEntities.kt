package com.lumina.reader.core.parser.common

import com.lumina.reader.core.text.Codepoints

/**
 * Character references used by HTML/XHTML/FB2 sources: the complete HTML 4
 * named set plus a few common HTML5 additions, and numeric references
 * (`&#8212;`, `&#x1F600;`, including code points above U+FFFF).
 */
internal object HtmlEntities {

    private const val TABLE =
        "quot 34 amp 38 apos 39 lt 60 gt 62 " +
            "nbsp 160 iexcl 161 cent 162 pound 163 curren 164 yen 165 brvbar 166 sect 167 uml 168 copy 169 " +
            "ordf 170 laquo 171 not 172 shy 173 reg 174 macr 175 deg 176 plusmn 177 sup2 178 sup3 179 " +
            "acute 180 micro 181 para 182 middot 183 cedil 184 sup1 185 ordm 186 raquo 187 frac14 188 " +
            "frac12 189 frac34 190 iquest 191 Agrave 192 Aacute 193 Acirc 194 Atilde 195 Auml 196 Aring 197 " +
            "AElig 198 Ccedil 199 Egrave 200 Eacute 201 Ecirc 202 Euml 203 Igrave 204 Iacute 205 Icirc 206 " +
            "Iuml 207 ETH 208 Ntilde 209 Ograve 210 Oacute 211 Ocirc 212 Otilde 213 Ouml 214 times 215 " +
            "Oslash 216 Ugrave 217 Uacute 218 Ucirc 219 Uuml 220 Yacute 221 THORN 222 szlig 223 agrave 224 " +
            "aacute 225 acirc 226 atilde 227 auml 228 aring 229 aelig 230 ccedil 231 egrave 232 eacute 233 " +
            "ecirc 234 euml 235 igrave 236 iacute 237 icirc 238 iuml 239 eth 240 ntilde 241 ograve 242 " +
            "oacute 243 ocirc 244 otilde 245 ouml 246 divide 247 oslash 248 ugrave 249 uacute 250 ucirc 251 " +
            "uuml 252 yacute 253 thorn 254 yuml 255 " +
            "OElig 338 oelig 339 Scaron 352 scaron 353 Yuml 376 fnof 402 circ 710 tilde 732 " +
            "ensp 8194 emsp 8195 thinsp 8201 hairsp 8202 zwnj 8204 zwj 8205 lrm 8206 rlm 8207 " +
            "hyphen 8208 dash 8208 ndash 8211 mdash 8212 horbar 8213 lsquo 8216 rsquo 8217 sbquo 8218 " +
            "ldquo 8220 rdquo 8221 bdquo 8222 dagger 8224 Dagger 8225 bull 8226 hellip 8230 mldr 8230 " +
            "permil 8240 prime 8242 Prime 8243 lsaquo 8249 rsaquo 8250 oline 8254 frasl 8260 euro 8364 " +
            "image 8465 weierp 8472 real 8476 trade 8482 numero 8470 alefsym 8501 " +
            "larr 8592 uarr 8593 rarr 8594 darr 8595 harr 8596 crarr 8629 lArr 8656 uArr 8657 rArr 8658 " +
            "dArr 8659 hArr 8660 forall 8704 part 8706 exist 8707 empty 8709 nabla 8711 isin 8712 " +
            "notin 8713 ni 8715 prod 8719 sum 8721 minus 8722 lowast 8727 radic 8730 prop 8733 infin 8734 " +
            "ang 8736 and 8743 or 8744 cap 8745 cup 8746 int 8747 there4 8756 sim 8764 cong 8773 " +
            "asymp 8776 ne 8800 equiv 8801 le 8804 ge 8805 sub 8834 sup 8835 nsub 8836 sube 8838 supe 8839 " +
            "oplus 8853 otimes 8855 perp 8869 sdot 8901 lceil 8968 rceil 8969 lfloor 8970 rfloor 8971 " +
            "lang 9001 rang 9002 loz 9674 spades 9824 clubs 9827 hearts 9829 diams 9830 " +
            "Alpha 913 Beta 914 Gamma 915 Delta 916 Epsilon 917 Zeta 918 Eta 919 Theta 920 Iota 921 " +
            "Kappa 922 Lambda 923 Mu 924 Nu 925 Xi 926 Omicron 927 Pi 928 Rho 929 Sigma 931 Tau 932 " +
            "Upsilon 933 Phi 934 Chi 935 Psi 936 Omega 937 alpha 945 beta 946 gamma 947 delta 948 " +
            "epsilon 949 zeta 950 eta 951 theta 952 iota 953 kappa 954 lambda 955 mu 956 nu 957 xi 958 " +
            "omicron 959 pi 960 rho 961 sigmaf 962 sigma 963 tau 964 upsilon 965 phi 966 chi 967 psi 968 " +
            "omega 969 thetasym 977 upsih 978 piv 982"

    private val named: Map<String, Int> by lazy {
        val parts = TABLE.split(' ').filter { it.isNotEmpty() }
        val map = HashMap<String, Int>(parts.size)
        var i = 0
        while (i + 1 < parts.size) {
            map[parts[i]] = parts[i + 1].toInt()
            i += 2
        }
        map
    }

    /** Windows-1252 meanings of the C1 range, as browsers apply them to `&#150;` etc. */
    private val c1Remap = mapOf(
        0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E, 0x85 to 0x2026,
        0x86 to 0x2020, 0x87 to 0x2021, 0x88 to 0x02C6, 0x89 to 0x2030, 0x8A to 0x0160,
        0x8B to 0x2039, 0x8C to 0x0152, 0x8E to 0x017D, 0x91 to 0x2018, 0x92 to 0x2019,
        0x93 to 0x201C, 0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014,
        0x98 to 0x02DC, 0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A, 0x9C to 0x0153,
        0x9E to 0x017E, 0x9F to 0x0178
    )

    /** Number of named entities known; exposed for tests. */
    val namedCount: Int get() = named.size

    /**
     * Decodes the body of a character reference (the part between `&` and
     * `;`). Returns null when it is not a known reference.
     */
    fun decode(name: String): String? {
        if (name.isEmpty()) return null
        if (name[0] == '#') return decodeNumeric(name.substring(1))
        val code = named[name] ?: named[name.lowercase()] ?: return null
        return Codepoints.toString(code)
    }

    private fun decodeNumeric(body: String): String? {
        if (body.isEmpty()) return null
        val value: Int? = if (body[0] == 'x' || body[0] == 'X') {
            if (body.length == 1 || body.length > 9) null else body.substring(1).toIntOrNull(16)
        } else {
            if (body.length > 9) null else body.toIntOrNull()
        }
        val code = value ?: return null
        val mapped = c1Remap[code] ?: code
        val valid = mapped in 1..0x10FFFF && mapped !in 0xD800..0xDFFF
        return if (valid) Codepoints.toString(mapped) else "�"
    }

    /** Decodes every reference in [text] in a single pass (`&amp;lt;` stays `&lt;`). */
    fun decodeAll(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi > i + 1 && semi - i <= 33) {
                    val decoded = decode(text.substring(i + 1, semi))
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
