#!/usr/bin/python3

# This script allows to tokenize Ukrinian text by sentences or words
# by invoking TokenizeText.groovy that uses LanguageTool API
# groovy (http://www.groovy-lang.org) needs to be installed and in the path
# Usage: tokenize_text.py <inputfile>

import os
import sys
import subprocess
import threading

ENCODING='utf-8'
SCRIPT_PATH=os.path.dirname(__file__) + '/../groovy/org/nlp_uk/tools'


if len(sys.argv) > 1:
    with open(sys.argv[1], encoding=ENCODING) as a_file:
        in_txt = a_file.read()
else:
    print("Usage: " + sys.argv[0] + " <inputfile>", file=sys.stderr)
    sys.exit(1)


def print_output(p):

#    error_txt = p.stderr.read().decode(ENCODING)
#    if error_txt:
#        print("stderr: ", error_txt, "\n", file=sys.stderr)

    print("output: ", p.stdout.read().decode(ENCODING))


# technically only needed on Windows
my_env = os.environ.copy()
my_env["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"


groovy_cmd = 'groovy.bat' if sys.platform == "win32" else 'groovy'
cmd = [groovy_cmd, SCRIPT_PATH + '/TokenizeText.groovy', '-i', '-', '-o', '-', '-w', '-u', '-q']
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stdin=subprocess.PIPE, stderr=subprocess.PIPE, env=my_env)

threading.Thread(target=print_output, args=(p,)).start()



p.stdin.write(in_txt.encode(ENCODING))
p.stdin.close()
