package mars

import mars.mips.dump.DumpFormat
import mars.mips.dump.DumpFormatLoader
import mars.mips.hardware.*
import mars.simulator.ProgramArgumentList
import mars.util.Binary
import mars.util.FilenameFinder
import mars.util.MemoryDump
import mars.venus.VenusUI
import net.sourceforge.argparse4j.ArgumentParsers
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.PrintStream
import java.util.*
import kotlin.system.exitProcess

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */
class MarsLaunch(var args: Array<String>)
{
    private var simulate = true
    private var displayFormat = HEXADECIMAL
    
    // display register name or address along with contents
    private var verbose = true
    
    // assemble only the given file or all files in its directory
    private var assembleProject = false
    
    // pseudo instructions allowed in source code or not.
    private var pseudo = true
    
    // MIPS delayed branching is enabled.
    private var delayedBranching = false
    
    // Whether assembler warnings should be considered errors.
    private var warningsAreErrors = false
    
    // Whether to start execution at statement labeled 'main'
    private var startAtMain = false
    
    // Whether to count and report number of instructions executed
    private var countInstructions = false
    
    // Whether to allow self-modifying code (e.g. write to text segment)
    private var selfModifyingCode = false

    private var instructionCount = 0

    // MARS command exit code to return if assemble error occurs
    private var assembleErrorExitCode = 0

    // MARS command exit code to return if simulation error occurs
    private var simulateErrorExitCode = 0
    
    private var registerDisplayList: ArrayList<String> = ArrayList()
    private var memoryDisplayList: ArrayList<String> = ArrayList()
    private var filenameList: ArrayList<String> = ArrayList()

    private var code: MIPSprogram? = null
    private var maxSteps = 0

    // stream for display of command line output
    private var out: PrintStream = System.out
    
    // each element holds 3 arguments for dump option
    private var dumpTriples: ArrayList<Array<String>>? = null 
    
    // optional program args for MIPS program (becomes argc, argv)
    private var programArgumentList: ArrayList<String>? = null

    init
    {
        // Start gui if "cmd" argument is not specified
        val gui = !args.contains("cmd")
        args = args.filter { it != "cmd" }.toTypedArray()

        Globals.initialize(gui)
        if (gui)
        {
            val p = ArgumentParsers.newFor("E-MARS GUI Arguments").build()
                .defaultHelp(true)
                .description("GUI Arguments for Azalea's Extended-MARS. Add 'cmd' argument for command-line usage")

            val open by p.string("-o", "--open", help = "Open a file on start")
            val runOnOpen by p.flag("--run-on-open", help = "Assemble and run a file when it's opened")
            p.parseToVars(args)

            // Start IDE
            val ui = VenusUI("MARS " + Globals.version)

            open?.let {
                ui.editor.editTabbedPane.openFile(File(it))
            }
        } 
        else
        {
            // running from command line.
            // assure command mode works in headless environment (generates exception if not)
            System.setProperty("java.awt.headless", "true")
            MemoryConfigurations.setCurrentConfiguration(MemoryConfigurations.getDefaultConfiguration())
            // do NOT use Globals.program for command line MARS -- it triggers 'backstep' log.
            code = MIPSprogram()
            maxSteps = -1
            if (parseCommandArgs(args))
            {
                if (runCommand())
                {
                    displayMiscellaneousPostMortem()
                    displayRegistersPostMortem()
                    displayMemoryPostMortem()
                }
                dumpSegments()
            }
            exitProcess(Globals.exitCode)
        }
    }

    /////////////////////////////////////////////////////////////
    // Perform any specified dump operations.  See "dump" option.
    //
    private fun dumpSegments()
    {
        if (dumpTriples == null)
        {
            return
        }
        for (i in dumpTriples!!.indices)
        {
            val triple = dumpTriples!![i]
            val file = File(triple[2])
            var segInfo = MemoryDump.getSegmentBounds(triple[0])
            // If not segment name, see if it is address range instead.  DPS 14-July-2008
            if (segInfo == null)
            {
                try
                {
                    val memoryRange = checkMemoryAddressRange(triple[0])
                    segInfo = arrayOfNulls(2)
                    segInfo[0] = Integer.valueOf(Binary.stringToInt(memoryRange!![0])) // low end of range
                    segInfo[1] = Integer.valueOf(Binary.stringToInt(memoryRange[1])) // high end of range
                } catch (nfe: NumberFormatException)
                {
                    segInfo = null
                } catch (npe: NullPointerException)
                {
                    segInfo = null
                }
            }
            if (segInfo == null)
            {
                out.println("Error while attempting to save dump, segment/address-range " + triple[0] + " is invalid!")
                continue
            }
            val loader = DumpFormatLoader()
            val dumpFormats = loader.loadDumpFormats()
            val format = DumpFormatLoader.findDumpFormatGivenCommandDescriptor(dumpFormats, triple[1])
            if (format == null)
            {
                out.println("Error while attempting to save dump, format " + triple[1] + " was not found!")
                continue
            }
            try
            {
                val highAddress = Globals.memory.getAddressOfFirstNull(
                    segInfo[0]!!.toInt(), segInfo[1]!!.toInt()
                ) - Memory.WORD_LENGTH_BYTES
                if (highAddress < segInfo[0]!!.toInt())
                {
                    out.println("This segment has not been written to, there is nothing to dump.")
                    continue
                }
                format.dumpMemoryRange(file, segInfo[0]!!.toInt(), highAddress)
            } catch (e: FileNotFoundException)
            {
                out.println("Error while attempting to save dump, file $file was not found!")
                continue
            } catch (e: AddressErrorException)
            {
                out.println("Error while attempting to save dump, file " + file + "!  Could not access address: " + e.address + "!")
                continue
            } catch (e: IOException)
            {
                out.println("Error while attempting to save dump, file $file!  Disk IO failed!")
                continue
            }
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Parse command line arguments.  The initial parsing has already been
    // done, since each space-separated argument is already in a String array
    // element.  Here, we check for validity, set switch variables as appropriate
    // and build data structures.  For help option (h), display the help.
    // Returns true if command args parse OK, false otherwise.
    private fun parseCommandArgs(args: Array<String>): Boolean
    {
        val displayMessagesToErrSwitch = "me"
        var argsOK = true
        var inProgramArgumentList = false
        programArgumentList = null
        if (args.isEmpty())
        {
            return true // should not get here...
        }
        // If the option to display MARS messages to standard erro is used,
        // it must be processed before any others (since messages may be
        // generated during option parsing).
        processDisplayMessagesToErrSwitch(args, displayMessagesToErrSwitch)
        if (args.size == 1 && args[0] == "h")
        {
            displayHelp()
            return false
        }
        var i = 0
        while (i < args.size)
        {

            // We have seen "pa" switch, so all remaining args are program args
            // that will become "argc" and "argv" for the MIPS program.
            if (inProgramArgumentList)
            {
                if (programArgumentList == null)
                {
                    programArgumentList = ArrayList()
                }
                programArgumentList!!.add(args[i])
                i++
                continue
            }
            // Once we hit "pa", all remaining command args are assumed
            // to be program arguments.
            if (args[i].equals("pa", ignoreCase = true))
            {
                inProgramArgumentList = true
                i++
                continue
            }
            // messages-to-standard-error switch already processed, so ignore.
            if (args[i].lowercase(Locale.getDefault()) == displayMessagesToErrSwitch)
            {
                i++
                continue
            }
            if (args[i].equals("dump", ignoreCase = true))
            {
                if (args.size <= i + 3)
                {
                    out.println("Dump command line argument requires a segment, format and file name.")
                    argsOK = false
                } else
                {
                    if (dumpTriples == null)
                    {
                        dumpTriples = ArrayList()
                    }
                    dumpTriples!!.add(arrayOf(args[++i], args[++i], args[++i]))
                    // simulate = false;
                }
                i++
                continue
            }
            if (args[i].equals("mc", ignoreCase = true))
            {
                val configName = args[++i]
                val config = MemoryConfigurations.getConfigurationByName(configName)
                if (config == null)
                {
                    out.println("Invalid memory configuration: $configName")
                    argsOK = false
                } else
                {
                    MemoryConfigurations.setCurrentConfiguration(config)
                }
                i++
                continue
            }
            // Set MARS exit code for assemble error
            if (args[i].lowercase(Locale.getDefault()).indexOf("ae") == 0)
            {
                val s = args[i].substring(2)
                try
                {
                    assembleErrorExitCode = Integer.decode(s).toInt()
                    i++
                    continue
                } catch (nfe: NumberFormatException)
                {
                    // Let it fall thru and get handled by catch-all
                }
            }
            // Set MARS exit code for simulate error
            if (args[i].lowercase(Locale.getDefault()).indexOf("se") == 0)
            {
                val s = args[i].substring(2)
                try
                {
                    simulateErrorExitCode = Integer.decode(s).toInt()
                    i++
                    continue
                } catch (nfe: NumberFormatException)
                {
                    // Let it fall thru and get handled by catch-all
                }
            }
            if (args[i].equals("d", ignoreCase = true))
            {
                Globals.debug = true
                i++
                continue
            }
            if (args[i].equals("a", ignoreCase = true))
            {
                simulate = false
                i++
                continue
            }
            if (args[i].equals("p", ignoreCase = true))
            {
                assembleProject = true
                i++
                continue
            }
            if (args[i].equals("dec", ignoreCase = true))
            {
                displayFormat = DECIMAL
                i++
                continue
            }
            if (args[i].equals("hex", ignoreCase = true))
            {
                displayFormat = HEXADECIMAL
                i++
                continue
            }
            if (args[i].equals("ascii", ignoreCase = true))
            {
                displayFormat = ASCII
                i++
                continue
            }
            if (args[i].equals("b", ignoreCase = true))
            {
                verbose = false
                i++
                continue
            }
            if (args[i].equals("db", ignoreCase = true))
            {
                delayedBranching = true
                i++
                continue
            }
            if (args[i].equals("np", ignoreCase = true) || args[i].equals("ne", ignoreCase = true))
            {
                pseudo = false
                i++
                continue
            }
            if (args[i].equals("we", ignoreCase = true))
            { // added 14-July-2008 DPS
                warningsAreErrors = true
                i++
                continue
            }
            if (args[i].equals("sm", ignoreCase = true))
            { // added 17-Dec-2009 DPS
                startAtMain = true
                i++
                continue
            }
            if (args[i].equals("smc", ignoreCase = true))
            { // added 5-Jul-2013 DPS
                selfModifyingCode = true
                i++
                continue
            }
            if (args[i].equals("ic", ignoreCase = true))
            { // added 19-Jul-2012 DPS
                countInstructions = true
                i++
                continue
            }
            if (args[i].indexOf("$") == 0)
            {
                if (RegisterFile.getUserRegister(args[i]) == null &&
                    Coprocessor1.getRegister(args[i]) == null
                )
                {
                    out.println("Invalid Register Name: " + args[i])
                } else
                {
                    registerDisplayList.add(args[i])
                }
                i++
                continue
            }
            // check for register name w/o $.  added 14-July-2008 DPS
            if (RegisterFile.getUserRegister("$" + args[i]) != null ||
                Coprocessor1.getRegister("$" + args[i]) != null
            )
            {
                registerDisplayList.add("$" + args[i])
                i++
                continue
            }
            if (File(args[i]).exists())
            {  // is it a file name?
                filenameList.add(args[i])
                i++
                continue
            }
            // Check for stand-alone integer, which is the max execution steps option
            try
            {
                Integer.decode(args[i])
                maxSteps = Integer.decode(args[i]).toInt() // if we got here, it has to be OK
                i++
                continue
            } catch (ignored: NumberFormatException)
            {
            }
            // Check for integer address range (m-n)
            try
            {
                val memoryRange = checkMemoryAddressRange(args[i])
                memoryDisplayList.add(memoryRange!![0]) // low end of range
                memoryDisplayList.add(memoryRange[1]) // high end of range
                i++
                continue
            } catch (nfe: NumberFormatException)
            {
                out.println("Invalid/unaligned address or invalid range: " + args[i])
                argsOK = false
                i++
                continue
            } catch (npe: NullPointerException)
            {
                // Do nothing.  next statement will handle it
            }
            out.println("Invalid Command Argument: " + args[i])
            argsOK = false
            i++
        }
        return argsOK
    }

    //////////////////////////////////////////////////////////////////////
    // Carry out the mars command: assemble then optionally run
    // Returns false if no simulation (run) occurs, true otherwise.
    private fun runCommand(): Boolean
    {
        var programRan = false
        if (filenameList.size == 0)
        {
            return programRan
        }
        try
        {
            Globals.getSettings().setBooleanSettingNonPersistent(Settings.DELAYED_BRANCHING_ENABLED, delayedBranching)
            Globals.getSettings()
                .setBooleanSettingNonPersistent(Settings.SELF_MODIFYING_CODE_ENABLED, selfModifyingCode)
            val mainFile = File(filenameList[0]).absoluteFile // First file is "main" file
            val filesToAssemble: ArrayList<*>
            if (assembleProject)
            {
                filesToAssemble = FilenameFinder.getFilenameList(mainFile.parent, Globals.fileExtensions)
                if (filenameList.size > 1)
                {
                    // Using "p" project option PLUS listing more than one filename on command line.
                    // Add the additional files, avoiding duplicates.
                    filenameList.removeAt(0) // first one has already been processed
                    val moreFilesToAssemble =
                        FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS)
                    // Remove any duplicates then merge the two lists.
                    var index2 = 0
                    while (index2 < moreFilesToAssemble.size)
                    {
                        for (index1 in filesToAssemble.indices)
                        {
                            if (filesToAssemble[index1] == moreFilesToAssemble[index2])
                            {
                                moreFilesToAssemble.removeAt(index2)
                                index2-- // adjust for left shift in moreFilesToAssemble...
                                break // break out of inner loop...
                            }
                        }
                        index2++
                    }
                    filesToAssemble.addAll(moreFilesToAssemble)
                }
            } else
            {
                filesToAssemble = FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS)
            }
            if (Globals.debug)
            {
                out.println("--------  TOKENIZING BEGINS  -----------")
            }
            val MIPSprogramsToAssemble = code!!.prepareFilesForAssembly(filesToAssemble, mainFile.absolutePath, null)
            if (Globals.debug)
            {
                out.println("--------  ASSEMBLY BEGINS  -----------")
            }
            // Added logic to check for warnings and print if any. DPS 11/28/06
            val warnings = code!!.assemble(MIPSprogramsToAssemble, pseudo, warningsAreErrors)
            if (warnings != null && warnings.warningsOccurred())
            {
                out.println(warnings.generateWarningReport())
            }
            RegisterFile.initializeProgramCounter(startAtMain) // DPS 3/9/09
            if (simulate)
            {
                // store program args (if any) in MIPS memory
                ProgramArgumentList(programArgumentList).storeProgramArguments()
                // establish observer if specified
                establishObserver()
                if (Globals.debug)
                {
                    out.println("--------  SIMULATION BEGINS  -----------")
                }
                programRan = true
                val done = code!!.simulate(maxSteps)
                if (!done)
                {
                    out.println("\nProgram terminated when maximum step limit $maxSteps reached.")
                }
            }
            if (Globals.debug)
            {
                out.println("\n--------  ALL PROCESSING COMPLETE  -----------")
            }
        } catch (e: ProcessingException)
        {
            Globals.exitCode = if (programRan) simulateErrorExitCode else assembleErrorExitCode
            out.println(e.errors().generateErrorAndWarningReport())
            out.println("Processing terminated due to errors.")
        }
        return programRan
    }

    //////////////////////////////////////////////////////////////////////
    // Check for memory address subrange.  Has to be two integers separated
    // by "-"; no embedded spaces.  e.g. 0x00400000-0x00400010
    // If number is not multiple of 4, will be rounded up to next higher.
    @Throws(NumberFormatException::class)
    private fun checkMemoryAddressRange(arg: String): Array<String>?
    {
        var memoryRange: Array<String>? = null
        if (arg.indexOf(rangeSeparator) > 0 &&
            arg.indexOf(rangeSeparator) < arg.length - 1
        )
        {
            // assume correct format, two numbers separated by -, no embedded spaces.
            // If that doesn't work it is invalid.
            memoryRange = arrayOf("", "")
            memoryRange[0] = arg.substring(0, arg.indexOf(rangeSeparator))
            memoryRange[1] = arg.substring(arg.indexOf(rangeSeparator) + 1)
            // NOTE: I will use homegrown decoder, because Integer.decode will throw
            // exception on address higher than 0x7FFFFFFF (e.g. sign bit is 1).
            if (Binary.stringToInt(memoryRange[0]) > Binary.stringToInt(memoryRange[1]) ||
                !Memory.wordAligned(Binary.stringToInt(memoryRange[0])) ||
                !Memory.wordAligned(Binary.stringToInt(memoryRange[1]))
            )
            {
                throw NumberFormatException()
            }
        }
        return memoryRange
    }

    /////////////////////////////////////////////////////////////////
    // Required for counting instructions executed, if that option is specified.
    // DPS 19 July 2012
    private fun establishObserver()
    {
        if (countInstructions)
        {
            val instructionCounter: Observer = object : Observer
            {
                private var lastAddress = 0
                override fun update(o: Observable, obj: Any)
                {
                    if (obj is AccessNotice)
                    {
                        val notice = obj
                        if (!notice.accessIsFromMIPS())
                        {
                            return
                        }
                        if (notice.accessType != AccessNotice.READ)
                        {
                            return
                        }
                        val m = notice as MemoryAccessNotice
                        val a = m.address
                        if (a == lastAddress)
                        {
                            return
                        }
                        lastAddress = a
                        instructionCount++
                    }
                }
            }
            try
            {
                Globals.memory.addObserver(instructionCounter, Memory.textBaseAddress, Memory.textLimitAddress)
            } catch (aee: AddressErrorException)
            {
                out.println("Internal error: MarsLaunch uses incorrect text segment address for instruction observer")
            }
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Displays any specified runtime properties. Initially just instruction count
    // DPS 19 July 2012
    private fun displayMiscellaneousPostMortem()
    {
        if (countInstructions)
        {
            out.println("\n\n$instructionCount")
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Displays requested register or registers
    private fun displayRegistersPostMortem()
    {
        var value: Int // handy local to use throughout the next couple loops
        // Display requested register contents
        out.println()
        val regIter: Iterator<String> = registerDisplayList.iterator()
        while (regIter.hasNext())
        {
            val reg = regIter.next()
            if (RegisterFile.getUserRegister(reg) != null)
            {
                // integer register
                if (verbose)
                {
                    out.print(reg + "\t")
                }
                value = RegisterFile.getUserRegister(reg)!!.value
                out.println(formatIntForDisplay(value))
            } else
            {
                // floating point register
                val fvalue = Coprocessor1.getFloatFromRegister(reg)
                val ivalue = Coprocessor1.getIntFromRegister(reg)
                var dvalue = Double.NaN
                var lvalue: Long = 0
                var hasDouble = false
                try
                {
                    dvalue = Coprocessor1.getDoubleFromRegisterPair(reg)
                    lvalue = Coprocessor1.getLongFromRegisterPair(reg)
                    hasDouble = true
                } catch (_: InvalidRegisterAccessException)
                {
                }
                if (verbose)
                {
                    out.print(reg + "\t")
                }
                if (displayFormat == HEXADECIMAL)
                {
                    // display float (and double, if applicable) in hex
                    out.print(
                        Binary.binaryStringToHexString(
                            Binary.intToBinaryString(ivalue)
                        )
                    )
                    if (hasDouble)
                    {
                        out.println(
                            "\t" +
                                Binary.binaryStringToHexString(
                                    Binary.longToBinaryString(lvalue)
                                )
                        )
                    } else
                    {
                        out.println()
                    }
                } else if (displayFormat == DECIMAL)
                {
                    // display float (and double, if applicable) in decimal
                    out.print(fvalue)
                    if (hasDouble)
                    {
                        out.println("\t" + dvalue)
                    } else
                    {
                        out.println()
                    }
                } else
                { // displayFormat == ASCII
                    out.print(Binary.intToAscii(ivalue))
                    if (hasDouble)
                    {
                        out.println(
                            "\t" +
                                Binary.intToAscii(Binary.highOrderLongToInt(lvalue)) +
                                Binary.intToAscii(Binary.lowOrderLongToInt(lvalue))
                        )
                    } else
                    {
                        out.println()
                    }
                }
            }
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Formats int value for display: decimal, hex, ascii
    private fun formatIntForDisplay(value: Int): String
    {
        val strValue: String
        strValue = when (displayFormat)
        {
            DECIMAL -> "" + value
            HEXADECIMAL -> Binary.intToHexString(value)
            ASCII -> Binary.intToAscii(value)
            else -> Binary.intToHexString(value)
        }
        return strValue
    }

    //////////////////////////////////////////////////////////////////////
    // Displays requested memory range or ranges
    private fun displayMemoryPostMortem()
    {
        var value: Int
        // Display requested memory range contents
        val memIter: Iterator<String?> = memoryDisplayList.iterator()
        var addressStart = 0
        var addressEnd = 0
        while (memIter.hasNext())
        {
            try
            { // This will succeed; error would have been caught during command arg parse
                addressStart = Binary.stringToInt(memIter.next().toString())
                addressEnd = Binary.stringToInt(memIter.next().toString())
            } catch (_: NumberFormatException)
            {
            }
            var valuesDisplayed = 0
            var addr = addressStart
            while (addr <= addressEnd)
            {
                if (addr < 0 && addressEnd > 0)
                {
                    break // happens only if addressEnd is 0x7ffffffc
                }
                if (valuesDisplayed % memoryWordsPerLine == 0)
                {
                    out.print(if (valuesDisplayed > 0) "\n" else "")
                    if (verbose)
                    {
                        out.print("Mem[" + Binary.intToHexString(addr) + "]\t")
                    }
                }
                try
                {
                    // Allow display of binary text segment (machine code) DPS 14-July-2008
                    value = if (Memory.inTextSegment(addr) || Memory.inKernelTextSegment(addr))
                    {
                        val iValue = Globals.memory.getRawWordOrNull(addr)
                        iValue?.toInt() ?: 0
                    } else
                    {
                        Globals.memory.getWord(addr)
                    }
                    out.print(formatIntForDisplay(value) + "\t")
                } catch (aee: AddressErrorException)
                {
                    out.print("Invalid address: $addr\t")
                }
                valuesDisplayed++
                addr += Memory.WORD_LENGTH_BYTES
            }
            out.println()
        }
    }

    ///////////////////////////////////////////////////////////////////////
    //  If option to display MARS messages to standard err (System.err) is
    //  present, it must be processed before all others.  Since messages may
    //  be output as early as during the command parse.
    private fun processDisplayMessagesToErrSwitch(args: Array<String>, displayMessagesToErrSwitch: String)
    {
        for (i in args.indices)
        {
            if (args[i].lowercase(Locale.getDefault()) == displayMessagesToErrSwitch)
            {
                out = System.err
                return
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////
    //  Display command line help text
    private fun displayHelp()
    {
        val segmentNames = MemoryDump.getSegmentNames()
        var segments = ""
        for (i in segmentNames.indices)
        {
            segments += segmentNames[i]
            if (i < segmentNames.size - 1)
            {
                segments += ", "
            }
        }
        val dumpFormats = DumpFormatLoader().loadDumpFormats()
        var formats = ""
        for (i in dumpFormats.indices)
        {
            formats += (dumpFormats[i] as DumpFormat).commandDescriptor
            if (i < dumpFormats.size - 1)
            {
                formats += ", "
            }
        }
        out.println("Usage:  Mars  [options] filename [additional filenames]")
        out.println("  Valid options (not case sensitive, separate by spaces) are:")
        out.println("      a  -- assemble only, do not simulate")
        out.println("  ae<n>  -- terminate MARS with integer exit code <n> if an assemble error occurs.")
        out.println("  ascii  -- display memory or register contents interpreted as ASCII codes.")
        out.println("      b  -- brief - do not display register/memory address along with contents")
        out.println("      d  -- display MARS debugging statements")
        out.println("     db  -- MIPS delayed branching is enabled")
        out.println("    dec  -- display memory or register contents in decimal.")
        out.println("   dump <segment> <format> <file> -- memory dump of specified memory segment")
        out.println("            in specified format to specified file.  Option may be repeated.")
        out.println("            Dump occurs at the end of simulation unless 'a' option is used.")
        out.println("            Segment and format are case-sensitive and possible values are:")
        out.println("            <segment> = $segments")
        out.println("            <format> = $formats")
        out.println("      h  -- display this help.  Use by itself with no filename.")
        out.println("    hex  -- display memory or register contents in hexadecimal (default)")
        out.println("     ic  -- display count of MIPS basic instructions 'executed'")
        out.println("     mc <config>  -- set memory configuration.  Argument <config> is")
        out.println("            case-sensitive and possible values are: Default for the default")
        out.println("            32-bit address space, CompactDataAtZero for a 32KB memory with")
        out.println("            data segment at address 0, or CompactTextAtZero for a 32KB")
        out.println("            memory with text segment at address 0.")
        out.println("     me  -- display MARS messages to standard err instead of standard out. ")
        out.println("            Can separate messages from program output using redirection")
        out.println("     np  -- use of pseudo instructions and formats not permitted")
        out.println("      p  -- Project mode - assemble all files in the same directory as given file.")
        out.println("  se<n>  -- terminate MARS with integer exit code <n> if a simulation (run) error occurs.")
        out.println("     sm  -- start execution at statement with global label main, if defined")
        out.println("    smc  -- Self Modifying Code - Program can write and branch to either text or data segment")
        out.println("    <n>  -- where <n> is an integer maximum count of steps to simulate.")
        out.println("            If 0, negative or not specified, there is no maximum.")
        out.println(" $<reg>  -- where <reg> is number or name (e.g. 5, t3, f10) of register whose ")
        out.println("            content to display at end of run.  Option may be repeated.")
        out.println("<reg_name>  -- where <reg_name> is name (e.g. t3, f10) of register whose")
        out.println("            content to display at end of run.  Option may be repeated. ")
        out.println("            The $ is not required.")
        out.println("<m>-<n>  -- memory address range from <m> to <n> whose contents to")
        out.println("            display at end of run. <m> and <n> may be hex or decimal,")
        out.println("            must be on word boundary, <m> <= <n>.  Option may be repeated.")
        out.println("     pa  -- Program Arguments follow in a space-separated list.  This")
        out.println("            option must be placed AFTER ALL FILE NAMES, because everything")
        out.println("            that follows it is interpreted as a program argument to be")
        out.println("            made available to the MIPS program at runtime.")
        out.println("If more than one filename is listed, the first is assumed to be the main")
        out.println("unless the global statement label 'main' is defined in one of the files.")
        out.println("Exception handler not automatically assembled.  Add it to the file list.")
        out.println("Options used here do not affect MARS Settings menu values and vice versa.")
    }

    companion object
    {
        private const val rangeSeparator = "-"
        private const val memoryWordsPerLine = 4 // display 4 memory words, tab separated, per line
        private const val DECIMAL = 0 // memory and register display format
        private const val HEXADECIMAL = 1 // memory and register display format
        private const val ASCII = 2 // memory and register display format
    }
}
