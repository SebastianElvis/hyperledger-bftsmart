#!/bin/bash

FABRIC_PATH=$GOPATH/src/github.com/hyperledger/fabric
START_COMMAND="java -cp dist/BFT-Proxy.jar:lib/* bft.BFTNode"

$START_COMMAND $1 10 $FABRIC_PATH/msp/sampleconfig/signcerts/peer.pem $FABRIC_PATH/msp/sampleconfig/keystore/key.pem 1000,2000
