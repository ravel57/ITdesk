#!/bin/bash

path=$(pwd)
cd ../../WebstormProjects/itdesk-front || exit

yarn install
yarn build

if [ -d "./dist/spa/" ]; then
    cp -r "./dist/spa/"* "$path/src/main/resources/static"
else
    echo "Building error" >&2
    exit 1
fi