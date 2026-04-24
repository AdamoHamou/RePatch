#!/bin/bash

# Check if width and height are provided
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <width> <height>"
    exit 1
fi

WIDTH=$1
HEIGHT=$2
REFRESH_RATE=60  # Default refresh rate

# Generate Modeline using gtf
MODELINE=$(gtf $WIDTH $HEIGHT $REFRESH_RATE | grep Modeline | sed -E 's/Modeline "//; s/_60\.00"//' )
echo $MODELINE

# Extract Mode Name (first quoted string)
MODE_NAME=$(echo $MODELINE | awk '{print $1}' | tr -d '"' | sed 's/_60.00//')

# Detect Display Name
DISPLAY_NAME=$(xrandr | grep " connected" | awk '{print $1}')

# Add New Mode
eval xrandr --newmode $MODELINE
xrandr --addmode $DISPLAY_NAME $MODE_NAME

echo "Resolution $WIDTH x $HEIGHT added successfully to $DISPLAY_NAME."
