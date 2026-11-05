#!/bin/bash

loader_version=0.20.1
installer_version=1.1.1

#declare -a vers
if [[ -z "$@" ]]; then
  vers=$(python3 - <<'PY'
import json
versions = []
with open('supported-versions.json') as source:
    for group in json.load(source).values():
        versions.extend(group['gameVersions'])
        versions.extend(group.get('additionalMappingVersions', []))
print(' '.join(dict.fromkeys(versions)))
PY
  ) || exit 1
else
  vers=$@
fi

echo $vers

./gradlew clean build || exit 1

mkdir run-mappingsGen || true
cd run-mappingsGen  || exit 1
rm -r mods
mkdir mods
rm channelMappings.json || true
if [[ -z "${MAPPINGS_GEN_FRESH}" ]]; then
  cp ../common/src/main/resources/raknetify-channel-mappings.json channelMappings.json
fi

for version in $vers
do
  rm -f mods/raknetify-*-all.jar mods/raknetify2-*.jar
  cp ../fabric-universal/build/libs/raknetify2-fabric-*.jar mods/ || exit 1

  launcher="fabric-server-mc${version}-loader${loader_version}-installer${installer_version}.jar"
  if [ -e "$launcher" ]; then
    echo "Using existing $launcher"
  else
    wget -O "$launcher" https://meta.fabricmc.net/v2/versions/loader/"$version"/$loader_version/$installer_version/server/jar || exit 1
  fi

  sleep 1

  touch .mapping-generation-start
  java -Draknetify.saveChannelMappings=true -Draknetify.saveChannelMappings.exit=true -jar "$launcher" || exit 1
  if [ ! channelMappings.json -nt .mapping-generation-start ]; then
    echo "Mapping generation did not write output for $version"
    exit 1
  fi
done

cp channelMappings.json ../common/src/main/resources/raknetify-channel-mappings.json
