#!/bin/env groovy

// This script reads all .txt files in given directory (default is "txt/") 
// and tries to find all with acceptable criterias for Ukrainian text (e.g. > 3k Ukrainian words)
// output files go into <dir>/good/
// NOTE:
// it also tries to fix broken encoding
// it also tries to clean up latin/cyrillic character mix
// it also tries to replace weird apostrophe characters with correct one (')
// it also tries to detect and skip two-column texts
// it also tries to merge some simple word wraps

//package org.nlp_uk.other

@Grab(group='org.languagetool', module='language-uk', version='5.0-SNAPSHOT')
@Grab(group='org.languagetool', module='language-uk', version='4.9')
@Grab(group='commons-cli', module='commons-cli', version='1.4')
@Grab(group='ch.qos.logback', module='logback-classic', version='1.2.3')


import java.util.regex.Pattern
import groovy.transform.Field
import groovy.io.FileVisitResult
import groovy.transform.CompileStatic
import org.apache.commons.cli.Options
import org.apache.commons.lang3.ArrayUtils
import org.languagetool.tagging.uk.*
import org.languagetool.*
//import org.nlp_uk.other.CleanTextNanu


class CleanText {
    // for higher quality text esp. short newspaper articles you need to keep it low ~100
    // for larger text with possible scanned sources you may want to go higher > 200
    // note: we count words with 2 letters and more
    int minUkrWordCount = 80

    Map<String, String> KNOWN_MIXES =
    [
        "ТаблоID": "Табло@@ID",
        "Фirtka": "Ф@@irtka"
    ]

    Map<String, String> latToCyrMap = [
        'a' : 'а',
        'c' : 'с',
        'e' : 'е',
        'i' : 'і',
        'o' : 'о',
        'p' : 'р',
        'x' : 'х',
        'y' : 'у',
        'A' : 'А',
        'B' : 'В',
        'C' : 'С',
        'E' : 'Е',
        'H' : 'Н',
        'I' : 'І',
        'K' : 'К',
        'M' : 'М',
        'O' : 'О',
        'P' : 'Р',
        'T' : 'Т',
        'X' : 'Х',
        'Y' : 'У',
        "á" : "а́",
        "Á" : "А́",
        "é" : "е́",
        "É" : "Е́",
        "í" : "і́",
        "Í" : "І́",
        "ḯ" : "ї́",
        "Ḯ" : "Ї́",
        "ó" : "о́",
        "Ó" : "О́",
        "ú" : "и́",
        "ý" : "у́",
        "Ý" : "У́"
    ]

    Map<String,String> cyrToLatMap = [:]

    @Lazy
    def tagger = { new UkrainianTagger() }()

    def options

    ThreadLocal<PrintStream> out = new ThreadLocal<>()
    ThreadLocal<ByteArrayOutputStream> outSw = new ThreadLocal<>()
    
            
    CleanText(def options) {
        this.options = options

        latToCyrMap.each{ String k, String v -> cyrToLatMap[v] = k }
        
        if( options.wordCount ) {
            minUkrWordCount = options.wordCount as int
        }
        println "Min Word limit: $minUkrWordCount"
    }

    void debug(str) {
        if( options.debug ) {
            println "\tDEBUG: $str"
        }
    }

    static int main(String[] args) {

        CliBuilder cli = new CliBuilder(usage: "CleanText [options] [<dir>]")

        cli.c(longOpt: 'clean', required: false, 'Clean old files in good/')
        cli.m(longOpt: 'modules', args:1, required: false, 'Extra cleanup: remove footnotes, page numbers etc. (supported modules: nanu)')
        cli.w(longOpt: 'wordCount', args:1, required: false, 'Minimum Ukrainian word count')
        cli.n(longOpt: 'allowTwoColumn', 'do not discard two-column text')
        cli.p(longOpt: 'parallel', 'Process files in parallel')
        cli.i(longOpt: 'input', args:1, required: false, 'Input file')
        cli.o(longOpt: 'output', args:1, required: false, 'Output file (default: input file with .out added before extention')
        cli.r(longOpt: 'recursive', required: false, 'Process directories recursively')
        cli.d(longOpt: 'debug', required: false, 'Debug output')
        cli.h(longOpt: 'help', 'Help - Usage Information')
        cli._(longOpt: 'dir', args:1, 'Directory to process *.txt in (default: txt/)')


        def options = cli.parse(args)

        if (!options) {
            System.exit(0)
        }

        if ( options.h ) {
            cli.usage()
            System.exit(0)
        }


        return new CleanText(options).process()
    }

    void println(String txt) {
        out.get().println(txt)
    }


    int process() {

        if( ! options.input ) {

            def dir = options.dir ? options.dir : "."

			File baseDir = new File(dir)
//			processDir(baseDir, baseDir)
			def files = []
			
			int maxDepth_ = options.recursive ? -1 : 0;
				
			baseDir.traverse(type: groovy.io.FileType.FILES, 
				maxDepth: maxDepth_,
				preDir       : { if (it.name == 'good') return FileVisitResult.SKIP_SUBTREE },
				) { File it ->
//				if( it.isDirectory() && it.name == "good" )
//					return FileVisitResult.SKIP_SUBTREE

				if( it.name.endsWith(".txt") ) {
					files << it
				}
			}
			
			println "Found ${files.size} txt files"
			
			def outDirName = prepareDir(baseDir)
			
			processFiles(files, baseDir, null)
        }
        else {
            def inputFilename = options.input
            def outputFilename = options.output ?: inputFilename.replaceFirst(/\..*?$/, '.good$0')

            def outFile = new File(outputFilename)
            if( inputFilename != outputFilename && outFile.exists() ) {
                println "Removing $outFile"
                outFile.delete()
            }

            def files = [ new File(inputFilename) ]

            processFiles(files, null, outputFilename)
        }
        
        println "Done!"

        return 0
    }

	private prepareDir(File baseDir) {
//		def outDirName = baseDir.path + "/good" + (dir.canonicalPath - baseDir.canonicalPath)

		def outDirName = baseDir.path + "/good"
		def outDirFile = new File(outDirName)
		outDirFile.mkdirs()

		if( ! outDirFile.isDirectory() ) {
			System.err.println "Output dir $outDirName does not exists"
			return 1
		}

		def prevFiles = outDirFile.list()

		if( prevFiles.size() > 0 ) {
			if( options.clean ) {
				println "Removing files from $outDirName"
				prevFiles.each { String f->
					new File(f).delete()
				}
			}
			else {
				System.err.println "Output dir $outDirName is not empty (rerun with --clean if you want to remove those files)"
				return 1
			}
		}

		return outDirName
	}

    void processFiles(files, baseDir, outFilename) {
        
        def stream = options.parallel ? files.parallelStream() : files.stream()

        if( options.parallel ) {
            println "Cleaning ${files.size()} files in parallel"
        }
        else {
            out.set(System.out)
            println "Cleaning ${files.size()} files"
        }

        stream.forEach{ file ->
            if( options.parallel ) {
                def byteStream = new ByteArrayOutputStream()
                outSw.set(byteStream)
                out.set(new PrintStream(byteStream))
            }

            println "Looking at ${file.name}"

            String text = file.text

            text = cleanUp(text, file, options)
            if( ! text ) {
                if( options.parallel ) {
                    out.get().flush()
                    System.out.println(outSw.get().toString("UTF-8"))
                }
                return
            }

            // NOTE: only counting words with 2 or more letters to filter out noised texts
            if( ! verifyWordCounts(text, minUkrWordCount) ) {
                if( options.parallel ) {
                    out.get().flush()
                    System.out.println(outSw.get().toString("UTF-8"))
                }
                return
            }


//            println "\tGOOD: $file.name\n"


			if( outFilename == null ) {
				def outDirName = baseDir.canonicalPath + "/good/"
				new File(outDirName).mkdirs()
				def outFilename2 = (file.canonicalPath.replaceFirst(baseDir.canonicalPath, outDirName))
				new File(new File(outFilename2).getParent()).mkdirs()
			    new File(outFilename2).text = text
			}
            else {
			    new File(outFilename).text = text
			}

            if( options.parallel ) {
                out.get().flush()
                System.out.println(outSw.get().toString("UTF-8"))
            }
        }

    }


    String removeSoftHyphens(String text) {
        if( text.contains("\u00AD") ) {
            println "\tremoving soft hyphens: "
//            text = text.replaceAll(/[ \t]*\u00AD[ \t]*([а-яіїєґА-ЯІЇЄҐ'ʼ’-]+)([,;.!?])?/, '$1$2')
//            text = text.replaceAll(/\u00AD(?!\n {10,}[А-ЯІЇЄҐ])(\n?[ \t]*)([а-яіїєґА-ЯІЇЄҐ'ʼ’-]+)([,;.!?])?/, '$2$3$1')
            text = text.replaceAll(/([а-яіїєґА-ЯІЇЄҐa-zA-Z])\u00AD+(\n[ \t]*)([а-яіїєґА-ЯІЇЄҐa-zA-Z'ʼ’-]+)([,;.!?])?/, '$1$3$4$2')
            text = text.replaceAll(/([а-яіїєґА-ЯІЇЄҐa-zA-Z:. ])\u00AD+([а-яіїєґА-ЯІЇЄҐa-zA-Z'ʼ’ -])/, '$1$2')
//            text = text.replaceAll(/(?i)([А-ЯІЇЄҐ:. ])\u00AD+([А-ЯІЇЄҐ'ʼ’ -])/, '$1$2')
//            text = text.replaceAll(/([А-ЯІЇЄҐA-Z])\u00AD(\n[ \t]*)([А-ЯІЇЄҐA-Z'ʼ’-]+)([,;.!?])?/, '$1$3$4$2')
           // text = text.replace('\u00AD', '-')
        }
        return text
    }
    
    
    //@CompileStatic
    String cleanUp(String text, File file, def options) {
        if( file.length() > 100 && file.bytes[0..3] == [0x50, 0x4B, 0x03, 0x04] ) {
            println "\tERROR: found zip file, possibly Word?"
            return null
        }

		if( text.contains("\r") ) {
			println "\tRemoving \r from text"
			text = text.replace("\r", "")
		}
		
        text = fixEncoding(text, file)
        if( ! text )
            return null

		if( ! checkEmptyLines(text) )
			return null 			
			

        // SINGLE LOW-9 QUOTATION MARK sometimes used as a comma
        text = text.replace('\u201A', ',')

        // weird ї and й via combining characters
        text = text.replaceAll(/[іi]\u0308/, 'ї')
        text = text.replace(/и\u0306/, 'й')

        // fix weird apostrophes
        text = text.replaceAll(/([бвгґдзкмнпрстфхш])[\"\u201D\u201F\u0022\u2018\u2032\u0313\u0384\u0092´`?*]([єїюя])/, /$1'$2/) // "

        text = removeSoftHyphens(text)

        if( text.contains('\u2028') ) {
            text = text.replaceAll(/\u2028\n?/, '\n')
        }


        text = fixCyrLatMix(text, file)
        if( ! text )
            return null


        if( ! options.allowTwoColumns ) {
			if( text.count("    ") >= 5 ) {
				def matcher = text =~ /(?ium)(.*?[а-яїієґ] {4,})[а-яіїєґ].{4}/
				matcher.find()
				def matchSize = matcher.size()
				if( matchSize >= 5
				&& matchSize > text.count("\n") * 3 / 4
				&& matcher[0][1].length() == matcher[2][1].length()
				&& matcher[0][1].length() == matcher[4][1].length() ) {
					println "\tERROR: two columns detected, skipping...:"
					println "\t${matcher[0][0]}\n\t${matcher[2][0]}\n\t${matcher[4][0]}"
					return null
				}
			}
        }

        if( options.modules ) {
            text = removeMeta(text, file, options)
        }

        text = removeSoftHyphens(text)

        text = fixDanglingHyphens(text, file)
    }

	boolean checkEmptyLines(text) {
		if( text.count("\n\n") > 5 ) {
			def matcher = text =~ /(?ius)[а-яїієґ0-9.—–-]\n\n[а-яіїєґ0-9]/
			matcher.find()
			def nonEmptyLines = text.split("\n").findAll { it =~ /[^\s]/ }
			if( nonEmptyLines.count { it.length() > 120 } > 5 ) {
				println "\tVery long lines found, probably unwrapped paragraphs..."
				return true
			}
			def nonEmptyLineCnt = nonEmptyLines.size()
//			println "${matcher.size()}::$nonEmptyLineCnt"
			if( matcher.size() > nonEmptyLineCnt / 5 ) {
				println "\tWARNING: Too many empty lines: ${matcher.size()}, total non-empty: $nonEmptyLineCnt"
				return false
			}  
		}
		return true
	}
	

    String removeMeta(String text, File file, def options) {

        if( options.modules == 'nanu' ) {
//            text = new CleanTextNanu(out.get()).removeMeta(text, file, options)
        }
        else
            throw new IllegalArgumentException("cleanup not supported for " + options.removeMeta)

        return text
    }


    boolean knownWord(String word) {
        try {
            return ! tagger.getAnalyzedTokens(word)[0].hasNoTag()
        }
        catch (Exception e) {
            System.err.println("Failed on word: " + word)
            throw e
        }
    }


    String removeMix(String text) {
        int count1 = 0
        int count2 = 0

        // latin digits

        while( text =~ /[XVI][ХІ]|[ХІ][XVI]/ ) {
            text = text.replaceAll(/([XVI])([ХІ])/, { all, lat, cyr ->
                lat + cyrToLatMap[cyr]
            })
            text = text.replaceAll(/([ХІ])([XVI])/, { all, cyr, lat ->
                cyrToLatMap[cyr] + lat
            })
        }

        // 1st tier

        // exclusively cyrillic letter followed by latin looking like cyrillic

        text = text.replaceAll(/([бвгґдєжзийклмнптфцчшщьюяБГҐДЄЖЗИЙЛПФХЦЧШЩЬЮЯ]['’ʼ]?)([aceiopxyABCEHIKMOPTXYáÁéÉíÍḯḮóÓúýÝ])/, { all, cyr, lat ->
            debug "mix: 1.1"
            count1 += 1
            cyr + latToCyrMap[lat]
        })

        // exclusively cyrillic letter preceeded by latin looking like cyrillic

        text = text.replaceAll(/([aceiopxyABCEHIKMOPTXYáÁéÉíÍḯḮóÓúýÝ])(['’ʼ]?[бвгґдєжзийклмнптфцчшщьюяБГҐДЄЖЗИЙЛПФХЦЧШЩЬЮЯ])/, { all, lat, cyr ->
            debug "mix: 1.2"
            count1 += 1
            latToCyrMap[lat] + cyr
        })


        text = text.replaceAll(/([bdfghjklmnrstuvwzDFGJLNQRSUVWZ]['’ʼ]?)([асеіорхуАВСЕНІКМНОРТХУ])/, { all, lat, cyr ->
            debug "mix: 1.3"
            count2 += 2
            lat + cyrToLatMap[cyr]
        })

        text = text.replaceAll(/([асеіорхуАВСЕНІКМНОРТХУ])(['’ʼ]?[bdfghjklmnrstuvwzDFGJLNQRSUVWZ])/, { all, cyr, lat ->
            debug "mix: 1.4"
            count2 += 2
            cyrToLatMap[cyr] + lat
        })


        // 2nd tier - try all cyrillic
        // if we convert all latin to cyrillic and find it in the dicitonary use conversion

        text = text.replaceAll(/[а-яіїєґА-ЯІЇЄҐ'ʼ’a-zA-ZáÁéÉíÍḯḮóÓúýÝ-]+/, { it ->

            if( it =~ /[а-яіїєґА-ЯІЇЄҐ]['’ʼ]?[aceiopxyABCEHIKMHOPTXYáÁéÉíÍḯḮóÓúýÝ]/
            || it =~ /[aceiopxyABCEHIKMHOPTXYáÁéÉíÍḯḮóÓúýÝ]['’ʼ]?[а-яіїєґА-ЯІЇЄҐ]/ ) {
                //            println "Found mix in: $it, known to LT: " + knownWord(it)
                if( ! knownWord(it) ) {
                    def fixed = it.replaceAll(/[aceiopxyABCEHIKMHOPTXYáÁéÉíÍḯḮóÓúýÝ]/, { lat -> latToCyrMap[lat] })
                    def fixedCleaned = fixed.replace('\u0301', '')
                    //                println "\tfixed $fixed known to LT: " + knownWord(fixedCleaned)
                    if( knownWord(fixedCleaned) ) {
                        count1 += 1
                        return fixed
                    }
                }
            }
            return it
        })


        // 3nd tier - least reliable

        // latin letter that looks like cyrillic between 2 cyrillics

        text = text.replaceAll(/([а-яіїєґА-ЯІЇЄҐ]['’ʼ]?)([aceiopxyABCEHIKMHOPTXYáÁéÉíÍḯḮóÓúýÝ])(['’ʼ]?[а-яіїєґА-ЯІЇЄҐ])/, { all, cyr, lat, cyr2 ->
            count1 += 1
            cyr + latToCyrMap[lat] + cyr2
        })

        // cyrillic letter that looks like latin between 2 latin

        text = text.replaceAll(/([a-zA-Z]['’ʼ]?)([асеіорхуАВСЕНІКМНОРТХУ])(['’ʼ]?[a-zA-Z])/, { all, lat, cyr, lat2 ->
            count2 += 2
            lat + cyrToLatMap[cyr] + lat2
        })

        println "\tconverted $count1 lat->cyr, $count2 cyr->lat"

        return text
    }

    @CompileStatic
    static String getSample(String text) {
        text[0..<Math.min(text.size(), 80)].replace('\n', '\\n')
    }


    @CompileStatic
    String fixEncoding(String text, File file) {
        if( text.contains("\u008D\u00C3") ) { // completly broken encoding for «ій»
            println "\tWARNING: nonfixable broken encoding found, garbage will be left in!"
        }

        if( text.contains("éîãî") ) {
            println "\tWARNING: broken encoding"

            // some text (esp. converted from pdf) have broken encoding in some lines and good one in others

            int convertedLines = 0
            int goodLines = 0
            text = text.split(/\n/).collect { String line->
                if( line.trim() && ! (line =~ /(?iu)[а-яіїєґ]/) ) {
                    line = new String(line.getBytes("cp1252"), "cp1251")
                    convertedLines += 1
                }
                else {
                    goodLines += 1
                }
                line
            }
            .join('\n')


            if( text.contains("éîãî") ) {
                println "\tERROR: still broken: encoding mixed with good one"
                return null
            }

            //        text = text.replaceAll(/([бвгґдзклмнпрстфхцшщ])\?([єїюя])/, '$1\'$2')

            println "\tEncoding fixed (good lines: $goodLines, convertedLines: $convertedLines, text: " + getSample(text)
        }
        else if( Collections.indexOfSubList(file.bytes.toList(), [(byte)0x20, (byte)0xB3, (byte)0x20]) != -1 ) {
			def cp1251Text = file.getText("cp1251")
			
			if( cp1251Text.contains(" і ") ) {
				println "\tWARNING: cp1251 encoding"

				text = cp1251Text

				if( text.size() < 200 ) {
					println "\tFile size < 200 chars, probaby cp1251 conversion didn't work, skipping"
					return null
				}

				println "\tEncoding converted: " + getSample(text)
			}
        }

        if( text.contains("\uFFFD") ) {
            println "\tWARNING: File contains Unicode 'REPLACEMENT CHARACTER' (U+FFFD)"
            //        return
        }

        return text
    }

//    @CompileStatic
    String fixCyrLatMix(String text, File file) {

        if( text =~ /[а-яіїєґА-ЯІЇЄҐ][a-zA-Zóáíýúé]|[a-zA-Zóáíýúé][а-яіїєґА-ЯІЇЄҐ]/ ) {
            KNOWN_MIXES.each { String k, String v ->
                text = text.replace(k, v)
            }

            if( text =~ /[а-яіїєґА-ЯІЇЄҐ][a-zA-Zóáíýúé]|[a-zA-Zóáíýúé][а-яіїєґА-ЯІЇЄҐ]/ ) {
                println "\tlatin/cyrillic mix in $file.name"

                text = removeMix(text)

                if( text =~ /[а-яіїєґА-ЯІЇЄҐ][a-zA-Zóáíýúé]|[a-zA-Zóáíýúé][а-яіїєґА-ЯІЇЄҐ]/ ) {
                    println "\tWARNING: still latin/cyrillic mix in $file.name"
                }
            }

            KNOWN_MIXES.each { String k, String v ->
                text = text.replace(v, k)
            }
        }

        // latin a and i
        text = text.replaceAll(/([а-яіїєґ]), a ([А-ЯІЇЄҐа-яіїєґ])/, '$1, а $2')
        text = text.replaceAll(/([а-яіїєґ]) i ([А-ЯІЇЄҐа-яіїєґ])/, '$1 і $2')

        return text
    }

//    @CompileStatic
    String fixDanglingHyphens(String text, File file) {
        if( text.contains("-\n") && text =~ /[а-яіїєґА-ЯІЇЄҐ]-\n/ ) {
            println "\tsuspect word wraps"
            def cnt = 0
            int cntWithHyphen = 0

            text = text.replaceAll(/([а-яіїєґА-ЯІЇЄҐ-]+)-\n([ \t]*)([«„"][а-яіїєґ'ʼ’-]+[»“"])([,;.!?])?/, { List<String> it ->
                it[1] + "-" + it[3] + (it[4] ?: "") + "\n" + it[2]
            })

            text = text.replaceAll(/([а-яіїєґА-ЯІЇЄҐ'ʼ’-]+)-\n([ \t]*)([а-яіїєґ'ʼ’-]+)([,;.!?])?/, { List<String> it ->

                //            println "== " + (it[1] + "-" + it[3]) + ", known: " + knownWord(it[1] + "-" + it[3])
                // consider words with two or more hyphens with one of them before end of line to be rare
                if( /*it[0].count('-') > 1 ||*/ ! knownWord(it[1] + "-" + it[3]) ) {
                    //                println "== " + (it[1] + it[3]) + ", known: " + knownWord(it[1] + it[3])
                    if( knownWord(it[1] + it[3]) ) {
                        cnt += 1
                        //                print "."
                        it[1] + it[3] + (it[4] ?: "") + "\n" + it[2]
                    }
                    else {
                        it[0]
                    }
                }
                else {
                    cntWithHyphen += 1
                    //                print ","
                    it[1] + "-" + it[3] + (it[4] ?: "") + "\n" + it[2]
                }
            })
            if( cnt > 0 || cntWithHyphen > 0 ) {
                println ""
            }
            println "\t\t$cnt word wraps removed, $cntWithHyphen newlines after hyphen removed"
        }

        if( text =~ /¬ *\n/ ) {
            println "\tsuspect word wraps with ¬:"
            text = text.replaceAll(/([а-яіїєґА-ЯІЇЄҐ'ʼ’-]+)¬ *\n([ \t]*)([а-яіїєґ'ʼ’-]+)/, '$1$3\n$2')
            println "\t\t¬ word wraps removed"
        }

        return text
    }

//    @CompileStatic
    private boolean verifyWordCounts(String text, int minUkrWordCount) {
        def ukrWords = text.split(/[^А-ЯІЇЄҐёа-яіїєґё'’ʼ-]+/).findAll{ it ==~ /[А-ЩЬЮЯІЇЄҐа-щьюяіїєґ][А-ЩЬЮЯІЇЄҐа-щьюяіїєґ'’ʼ-]+/ }
        int ukrWordCount = ukrWords.size()
        if( ukrWordCount < minUkrWordCount ) {
            println "\tERROR: Less than $minUkrWordCount Ukrainian words ($ukrWordCount): " + getSample(text) // + "\n\t" + ukrWords
            return false
        }
        println "\tUkrainian word count: $ukrWordCount"
        //    if( ukrWordCount < 300 ) println "\t\t: " + ukrWords

        // for really big text counting chars takes long time
        // we'll just evaluate first 1000k

        def lowerTextSample = text.toLowerCase().take(1024*1024)
        int ukrLetterCount = lowerTextSample.findAll { String it -> "іїєґ".contains(it) } .size()
        int rusLetterCount = lowerTextSample.findAll { String it -> "ыэъё".contains(it) } .size()

        def minUkrainianLetters = minUkrWordCount >= 20 ? minUkrWordCount / 20 : 0
        if( ukrLetterCount < minUkrainianLetters ) {
            println "\tERROR: Less than $minUkrainianLetters Ukrainian letters ($ukrLetterCount): " + getSample(text)
            return false
        }

        if( ukrLetterCount < rusLetterCount ) {
            println "\tERROR: Less Ukrainian letters ($ukrLetterCount) than Russian ($rusLetterCount), probably russian text: " + getSample(text)
            return false
        }

        return true
    }
}
