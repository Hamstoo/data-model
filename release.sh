#!/usr/bin/env bash

# Environment variables used by build.sh and release.sh.
# See: How to Version your Docker Images
#      https://medium.com/travis-on-docker/how-to-version-your-docker-images-1d5c577ebf54
set -ex

# ensure we're up to date
git pull

# bump version (TODO: the script inside this image is a binary, rewrite it in Python)
# this also (1) removes any -SNAPSHOT from the end of the version string and (2) updates the file w/ the new version
docker run --rm -v "$PWD":/app treeder/bump patch
version=`cat VERSION`
echo "version: $version"

# run build (must `source` to get env vars)
sbt publishLocal #-Dversion=$version -- this is now looked up by build.sbt directly

# tag release version and push
git add -A
git commit -m "version $version"
git tag -a "$version" -m "version $version"
git push
git push --tags

# overwrite VERSION file with -SNAPSHOT appended version
echo "$version-SNAPSHOT" > VERSION

# re-commit dev/SNAPSHOT version and push (must push twice to trigger CircleCI twice)
git commit -am "version $version-SNAPSHOT"
git push
