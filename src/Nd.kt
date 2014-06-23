/**
 * Created by keki on 6/16/14.
 */


import java.util.LinkedList
import java.util.ArrayList
import sun.misc.Unsafe.getUnsafe
import sun.misc.Unsafe

fun xrange (low:Int) : IntRange {
    return 0..low-1
}

fun range (low : Int) : List<Int> {
    var ret = ArrayList<Int>(low)
    for (x in xrange(low)) {
        ret.add(x)
    }
    return ret
}

val unsafe = unsf()
fun unsf() : Unsafe {
    val f = javaClass<Unsafe>().getDeclaredField("theUnsafe"); //Internal reference
    f.setAccessible(true)
    return f.get(null) as Unsafe
}


class Slice (val start : Int, val end : Int, val incr : Int, val size : Int) {
    fun get(index : Int) : Int{
        if (index < size) {
            return start + index * incr
        }
        else throw Exception("Index out of bounds")
    }
}

class SliceBuilder(var start : Int? = null, var end : Int? = null, var incr : Int? = null) {
    fun build(dimSize : Int) : Slice {
        if(incr==null)
            incr = 1
        else if (incr==0)
            throw Exception("increment cannot be zero")
        if(start==null)
            if (incr!! > 0)
                start = 0
            else
                start = dimSize-1
        else {
            if (start!! < 0)
                start = Math.max(dimSize + start!!, -1)
            else
                start = Math.min(start!!, dimSize)
        }
        if(end==null)
            if (incr!!>0)
                end = dimSize
            else
                end = -1
        else {
            if (end!! < 0)
                end = Math.max(dimSize + end!!, -1)
            else
                end = Math.min(end!!, dimSize)
        }
        val diff = (end!! - start!!)
        val size = Math.max(0, (diff / incr!!) + if (diff % incr!! > 0) 1 else 0)

        return Slice(start!!, end!!, incr!!, size)
    }
}

abstract class Nd(val shape : Array<Int>) {
    val nDims = shape.size
    val size = shape.reduce {(x,y)-> x*y}

    //todo check that coords are not out of bounds
    //add a broadcast flag
    abstract fun get(coords : Array<Int>) : Float
    abstract fun set(coords : Array<Int>, value: Float)

    fun plus(other : Nd) : NdArray {
        return NdArray(shape) {(coord) ->
            get(coord) + other.get(coord)
        }
    }

    fun times(other: Nd) : NdArray {
        return NdArray(shape) {(coord) ->
            get(coord) * other.get(coord)
        }
    }
    fun get(coords : Array<Any>) : Nd {
        if (coords.size != nDims)
            throw Exception("Number of coordinates does not match number of dimensions")
        val newShape = ArrayList<Int>(nDims)
        for (x in 0..nDims-1) {
            val tmp = coords[x]
            when (tmp) {
                is SliceBuilder -> {val s = tmp.build(shape[x]); newShape.add(s.size); coords[x] = s}
                is Array<*> -> newShape.add(tmp.size)
            }
        }

        return NdView(newShape.copyToArray(), coords, this)
    }
    fun set(coords : Array<Any>, values : Nd) {
        if (coords.size != nDims)
            throw Exception("Number of coordinates does not match number of dimensions")
        val newShape = ArrayList<Int>(nDims)
        for (x in 0..nDims-1) {
            val tmp = coords[x]
            when (tmp) {
                is SliceBuilder -> {val s = tmp.build(shape[x]); newShape.add(s.size); coords[x] = s}
                is Array<*> -> newShape.add(tmp.size)
            }
        }

        varforview(values, NdView(newShape.copyToArray(), coords, this))
    }
}

class NdView(shape : Array<Int>, val transform: Array<out Any>, val source : Nd) : Nd(shape) {

    fun new2old (coords : Array<Int>) : Array<Int> {
        val srcCoords = Array<Int>(source.nDims) {0}
        var ctr = 0
        for (x in 0..source.nDims-1) {
            val tmp = transform[x]
            when (tmp) {
                is Slice -> srcCoords[x] = tmp[coords[ctr++]]
                is Array<Int> -> srcCoords[x] = tmp[coords[ctr++]]
                else -> srcCoords[x] = transform[x] as Int
            }
        }
        return srcCoords
    }
    override fun get(coords : Array<Int> ) : Float{
        return source.get(new2old((coords)))
    }

    override fun set (coords : Array<Int>, value : Float) {
        source.set(new2old(coords), value)
    }


}

class NdArray(shape : Array<Int>, val kernel : (Array<Int>) -> Float) : Nd(shape) {
    var isMaterialized = false
    var address = 0L
    var getElement : (Array<Int>) -> Float  = {(coords) ->
        kernel(coords)
    }
    override fun get(coords : Array<Int>) : Float {
        return getElement(coords)
    }

    var setElement : (Array<Int>, Float) -> Unit = {(coords, value) ->
        materialize();
        this.setElement(coords, value)
    }
    override fun set(coords : Array<Int>, value : Float) : Unit {
        setElement(coords, value)
    }

    fun materialize() {
        if (isMaterialized)
            return
        var longAccum = 1L
        for (x in xrange(nDims)) {
            longAccum *= shape[x]
        }

        if (address!=0L) {
            print("disallocating memory...")
            unsafe.freeMemory(address)
            println("done")

        }
        print("allocating memory...")
        address = unsafe.allocateMemory(longAccum * 4)
        println("done")
        print("materializing Nd array...")
        val strides = IntArray(nDims);
        var accum = 1
        for (x in nDims - 1 downTo 0) {
            strides[x] = accum
            accum *= shape[x]
        }
        varfor(address, shape, kernel)

        val getElement: (Array<Int>) -> Float = {(coords) ->
            if (coords.size != nDims) {
                throw Exception("wrong number of dimensions")
            }
            var acc = 0L
            for (x in 0..nDims-1) {
                acc += strides[x] * coords[x]
            }
            unsafe.getFloat(address + acc * 4)
        }
        this.getElement = getElement

        val setElement: (Array<Int>, Float) -> Unit = {(coords, value) ->
            var acc = 0
            for (x in 0..nDims-1) {
                acc += strides[x] * coords[x]
            }
            unsafe.putFloat(address + acc * 4, value)
        }

        this.setElement = setElement
        isMaterialized = true
        println("done")
    }
}

fun main (args : Array<String>) {

    val s = SliceBuilder(-1, 4, -1).build(20)
    for (x in xrange(s.size))
        println(s[x])

    val X = NdArray(array(300, 200000, 8)) {(coords : Array<Int>)  ->
       ((coords[0] + 1) *
        (coords[1] + 1) *
        (coords[2] + 1)).toFloat()
    }

    val Y = NdArray(array(300, 200000, 8)) {(coords: Array<Int>) ->
        ((coords[0]) +
         (coords[1]) +
         (coords[2])).toFloat()
    }
    val Z = X.get(array(SliceBuilder() as Any, SliceBuilder() as Any, SliceBuilder() as Any))
//    println("========Z[array(0,0,0)] = 0f")
//    Z[array(0,0,0)] = 0f
    val W = X+Z
    println("========W[*] = W[#]")
    W[array(SliceBuilder(0,null,2) as Any, SliceBuilder(0, null, 2) as Any, SliceBuilder(0, null, 2) as Any)] =
        W[array(SliceBuilder(1,null,2) as Any, SliceBuilder(1, null, 2) as Any, SliceBuilder(1, null, 2) as Any)]
    val U = W*Z
    val V = U+X
//    println("================Materializing Z")
//    Z.materialize()

    println ("X = " + X.get(array(4, 5, 6)))
    println ("Y = " + Y.get(array(4, 5, 6)))
    println ("Z = " + Z.get(array(2, 1, 2)))
//    println("================Materializing V")
//    V.materialize()
////    println("================Materializing W")
////    W.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing U")
//    U.materialize()
//    println("================Materializing V")
//    V.materialize()
//    println("================Materializing Y")
//    Y.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing Z")
//    Z.materialize()
//    println("================Materializing Y")
//    Y.materialize()
//    println("================Materializing Y")
//    Y.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing Z")
//    Z.materialize()
//    println ("X = " + X.get(array(4, 5, 6)))
//    println ("Y = " + Y.get(array(4, 5, 6)))
//    println ("Z = " + Z.get(array(4, 5, 6)))
//    println("================Materializing Z")
//    Z.materialize()
//    println("================Materializing X")
//    X.materialize()
//    println("================Materializing Y")
//    Y.materialize()
//    println("================Materializing Z")
//    Z.materialize()
//    println("================Materializing W")
//    W.materialize()
//    println("================Materializing W")
//    W.materialize()
//
//    println("Z = "+ Z.get(array(4, 5, 6)))
}

//fun main (args : Array<String>) {
//    print("allocating space for NdArray...")
//    val n = FloatArray(200*300*100*100)
//    println("done")
//    print("initializing NdArray...")
//    for (x in xrange(200*300*100*100))
//        n[x] = {5f}()
//    println("done")
//}

fun varforview(source : Nd, target : NdView) {
    val nDims = target.shape.size
    fun varforviewHelper (dimIx  : Int,
                          coords: Array<Int>) {
        if (dimIx == nDims)
            target.set(coords, source.get(coords))
        else {
            val maxOfDimension = target.shape[dimIx]
            for (x in xrange(maxOfDimension)) {
                coords[dimIx] = x
                varforviewHelper(dimIx+1, coords)
            }
        }
    }

    val coords = Array<Int>(nDims) {0}
    varforviewHelper(0, coords)
}


fun varfor(address: Long,
           shape  : Array<Int>,
           kernel : (Array<Int>) -> Float) {
    var index = 0L
    val nDims = shape.size

    fun varforHelper (dimIx  : Int,
                      coords : Array<Int>) {
        if (dimIx + 1 == nDims) {
            val maxOfDimension = shape[dimIx]
            for (x in xrange(maxOfDimension)) {
                coords[dimIx] = x
                unsafe.putFloat(address + index * 4, kernel(coords))
                index++
            }
        }
        else {
            val maxOfDimension = shape[dimIx]
            for (x in xrange(maxOfDimension)) {
                coords[dimIx] = x
                varforHelper(dimIx+1, coords)
            }
        }
    }

    val coords = Array<Int>(nDims) {0}
    varforHelper(0, coords)
}
