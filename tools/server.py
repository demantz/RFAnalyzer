#!/usr/local/bin/python
import struct
import time
import socket
from errno import EWOULDBLOCK
import random
packet_loose_prob=0.0 # probability of loosing packet ftom 0 to 1

refClk = 122880000
rx_packet_size = 1442
dump_name = """C:\\Users\Pavel\\Desktop\\HiQSDR\\1.iq"""
rate = 48000

samples_per_packet = (rx_packet_size-2)/(3+3) # 2 bytes for header, I and Q interleaved, 3 bytes each

def bytes2int(arr, offset):
    return \
        ord(arr[offset + 3]) << 24 \
        | ord(arr[offset + 2]) << 16 \
        | ord(arr[offset + 1]) << 8 \
        | ord(arr[offset])


def tunePhase2Freq(phase):
    return (phase - 0.5) * refClk / (1 << 32)


def RXCtrl2SampleRate(ctrl):
    return refClk / ((ctrl - 1)*64)


def sampleRate2delay(rate):
    return samples_per_packet/rate


def print_cmd(sender, cmd):
    print "Received command:"
    print "\tId: " + cmd[0] + cmd[1]
    print "\tRX Frequency: " + str(tunePhase2Freq(bytes2int(cmd, 2)))
    print "\tTX Frequency: " + str(tunePhase2Freq(bytes2int(cmd, 6)))
    print "\tTX Level: " + str(ord(cmd[10]))
    print "\tTX Ctrl: " + bin(ord(cmd[11]))
    rate = RXCtrl2SampleRate(ord(cmd[12]))
    print "\tRX Sample rate: " + str(rate) + "(" + str(ord(cmd[12])) + ")"
    print "\tFirmware ver.: " + str(ord(cmd[13]))
    print "\tPreselector: " + bin(ord(cmd[14]))
    print "\tAttenuator: " + bin(ord(cmd[15]))
    print "\tAntenna: " + str(ord(cmd[16]))

import itertools, sys
spinner = itertools.cycle(['-', '/', '|', '\\'])


def spin():
    sys.stdout.write(spinner.next())  # write the next character
    sys.stdout.flush()                # flush stdout buffer (actual character display)
    sys.stdout.write('\b')            # erase the last written char


host = "0.0.0.0"
cmdPort = 48248
rxPort = 48247
cmd_udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
rx_udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

print 'Staring HiQSDR emulation server on '+host
print 'Predefined package loss rate: '+str(packet_loose_prob)

cmd_udp_sock.bind((host, cmdPort))
cmd_udp_sock.setblocking(False)
print 'Command interface on ' + str(host) + ':' + str(cmdPort)

rx_udp_sock.bind((host, rxPort))
rx_udp_sock.setblocking(False)
print 'RX interface on ' + str(host) + ':' + str(rxPort)

send = False
file = open(dump_name, "rb")
while True:
    try:
        cmd, cmd_sender = cmd_udp_sock.recvfrom(22)
        """if no exception thrown -- we received command"""
        print_cmd(cmd_sender, cmd)
        """ send it back, as would real HiQSDR do
        (but it will also check for correctness, we won't)"""
        cmd_udp_sock.sendto(cmd, cmd_sender)
    except socket.error, e:
        pass
    try:
        rxCmd, requester = rx_udp_sock.recvfrom(2)
        """if no exception thrown -- we received command"""
        if(rxCmd[0] == 'r' and rxCmd[1] == 'r'):
            print "Received request to start sending"
            send = True
        elif(rxCmd[0] == 's' and rxCmd[1] == 's'):
            print "Received request to stop sending"
            send = False
        else:
            print "Received unknown command on RX interface"
    except socket.error, e:
        pass
    if(send and random.random()>=packet_loose_prob):
        packet = file.read(rx_packet_size)
        if(len(packet) < 1442):
            print "File ended, reset to the beginning..."
            file.seek(0, 0)
            packet = file.read(rx_packet_size)
        try:
            rx_udp_sock.sendto(packet, requester)
            spin()
        except socket.error, e:
            """if it's just a full network buffer -- wait a little"""
            if(e.errno == EWOULDBLOCK):
                time.sleep(0.1)
                pass
            else:
                print "sendto: Socket exception[{0}]: {1}".format(e.errno, e.strerror)
                raise
    time.sleep(sampleRate2delay(rate))  # 200 Pps, 48000Sps
