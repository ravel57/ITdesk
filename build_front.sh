#!/bin/bash

path=$(pwd)
cd ../../WebstormProjects/itdesk-front || exit

yarn install
yarn build

if [ -d "./dist/spa/" ]; then
    rm -rf "$path/src/main/webapp/js"
    rm -rf "$path/src/main/webapp/css"
    rm -rf "$path/src/main/webapp/icons"
    rm -rf "$path/src/main/webapp/fonts"
    cp -r "./dist/spa/"* "$path/src/main/webapp/"

    rm "$path/src/main/webapp/index.html"

    cd "$path/src/main/webapp/js" || exit
    mv app.*.js app.js
    mv vendor.*.js vendor.js

    cd "$path/src/main/webapp/css" || exit
    mv app.*.css app.css
    mv vendor.*.css vendor.css
else
    echo "Building error" >&2
    exit 1
fi