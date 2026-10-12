package remy.atvremote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

// minimal protobuf, varint and length-delimited fields only - that's all polo.proto / remotemessage.proto use
class Pb {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, v: Long) = apply { varint(out, (field shl 3).toLong()); varint(out, v) }
    fun int(field: Int, v: Int) = int(field, v.toLong())
    fun bytes(field: Int, v: ByteArray) = apply { varint(out, (field shl 3 or 2).toLong()); varint(out, v.size.toLong()); out.write(v) }
    fun str(field: Int, v: String) = bytes(field, v.toByteArray())
    fun msg(field: Int, v: Pb) = bytes(field, v.toBytes())
    fun toBytes(): ByteArray = out.toByteArray()

    companion object {
        fun varint(o: OutputStream, value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            o.write(v.toInt())
        }

        fun readVarint(i: InputStream): Long {
            var r = 0L; var s = 0
            while (true) {
                val b = i.read()
                if (b < 0) throw java.io.EOFException()
                r = r or ((b and 0x7F).toLong() shl s); s += 7
                if (b < 0x80) return r
            }
        }

        // field number -> Long (varint) or ByteArray (length-delimited)
        fun parse(b: ByteArray): Map<Int, Any> {
            val i = b.inputStream(); val m = HashMap<Int, Any>()
            while (i.available() > 0) {
                val key = readVarint(i)
                m[(key ushr 3).toInt()] = when ((key and 7).toInt()) {
                    0 -> readVarint(i)
                    2 -> ByteArray(readVarint(i).toInt()).also { i.read(it) }
                    1 -> i.skip(8)
                    5 -> i.skip(4)
                    else -> error("bad wire type")
                }
            }
            return m
        }

        // varint length prefix framing, same on both transports
        fun write(o: OutputStream, msg: Pb) = synchronized(o) {
            val b = msg.toBytes(); varint(o, b.size.toLong()); o.write(b); o.flush()
        }

        fun read(i: InputStream): Map<Int, Any> {
            val b = ByteArray(readVarint(i).toInt()); var n = 0
            while (n < b.size) n += i.read(b, n, b.size - n).also { if (it < 0) throw java.io.EOFException() }
            return parse(b)
        }
    }
}

fun Map<Int, Any>.sub(f: Int) = (this[f] as? ByteArray)?.let { Pb.parse(it) }
fun Map<Int, Any>.long(f: Int) = this[f] as? Long
