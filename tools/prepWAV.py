#!/usr/bin/python

##########################################
# Tool for prepending a SDR#-compatible 
# WAV header to a HackRF IQ file.
#
# usage: 
# ./prepWAV.py <IQ-file IN> <WAV-file OUT>
#
# Author:  Dennis Mantz
# Date:    18.04.2015
# Version: 1.0
##########################################

import struct
import argparse
import textwrap
import os

def writeWavHeader(file, datasize, samplerate):
    file.write(struct.pack('4s', "RIFF"))
    file.write(struct.pack('I', datasize+36))
    file.write(struct.pack('4s', "WAVE"))
    file.write(struct.pack('4s', "fmt "))
    file.write(struct.pack('I', 16))
    file.write(struct.pack('H', 1))
    file.write(struct.pack('H', 2))
    file.write(struct.pack('I', samplerate))
    file.write(struct.pack('I', samplerate*2))
    file.write(struct.pack('H', 2))
    file.write(struct.pack('H', 8))
    file.write(struct.pack('4s', "data"))
    file.write(struct.pack('I', datasize))

def copyFileContent(inFile, outFile):
    outFile.write(inFile.read())

def prepareArgParser () :
    """Sets up the ArgumentParser for the commandline arguments.
        returns: ArgumentParser object """
    
    parser = argparse.ArgumentParser(formatter_class=argparse.RawTextHelpFormatter,
        description=textwrap.dedent('''\
        This tool prepends a SDR# compatible WAV header to HackRF IQ files.'''),
        epilog=textwrap.dedent('''\
        License: GPLv2
        Copyright (C) 2015  Dennis Mantz'''))
    
    parser.add_argument("in_file", 
                    help="specify the input file (HackRF IQ)")
    parser.add_argument("out_file",
                    help="specify the output file (SDR# WAV)")
    parser.add_argument("samplerate", type=int,
                    help="specify the sample rate of the input file")
    return parser

if(__name__ == "__main__"):
    parser = prepareArgParser()
    args = parser.parse_args()

    filesize = os.path.getsize(args.in_file)
    outFile = open(args.out_file, 'wb')
    inFile = open(args.in_file, 'rb')
    print("Write WAV header...")
    writeWavHeader(outFile, filesize, args.samplerate)
    print("Write samples (" + str(filesize) + " bytes)...")
    copyFileContent(inFile,outFile)
    outFile.close()
    inFile.close()
    print("Done.")
