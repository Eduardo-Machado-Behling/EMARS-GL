import mars.Globals
import mars.mips.hardware.MemoryConfigurations
import kotlin.system.measureTimeMillis

fun main(args: Array<String>)
{
    Globals.initialize(true)
    MemoryConfigurations.setCurrentConfiguration(MemoryConfigurations.getDefaultConfiguration())
    println(measureTimeMillis {
        for (n in 0..100)
            for (i in 0..128*256)
                Globals.memory.getWord(0x10008000 + i * 4)
    })
}
