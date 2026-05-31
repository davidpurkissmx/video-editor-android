# Video Editor Android

Editor de video para Android con **FFmpeg + libx264 nativo**. Procesa el video 100% en el dispositivo: solo se envía el audio al servidor para transcripción (ElevenLabs) y generación de EDL (DeepSeek/Claude).

## Arquitectura

```
Teléfono (Android)                    Servidor (video.davidpurkiss.com)
─────────────────                    ─────────────────────────────
1. Compresión (FFmpeg libx264)
2. Extracción de audio WAV ──────►   3. Transcripción (ElevenLabs Scribe)
                                     4. EDL (DeepSeek / Claude) ◄───
5. Segmentos + fades (FFmpeg)
6. Concatenación (concat demuxer)
7. Loudnorm (-14 LUFS)
8. Video final en Películas/
```

## FFmpeg binario

Compilado específicamente para este proyecto (arm64-v8a, Android NDK 26):

```
--enable-libx264
--enable-encoder='aac,mpeg4,pcm_s16le,libx264'
--enable-decoder='aac,h264,hevc,mp3,mpeg4,pcm_s16le'
--enable-filter='scale,afade,concat,eq,format,nullsrc,loudnorm,anull,fps,aresample,aformat'
--enable-bsf='h264_mp4toannexb,aac_adtstoasc'
--enable-demuxer='aac,mp3,mov,mp4,mpegvideo,wav,concat'
--enable-muxer='mp4,wav,adts,null'
```

Reconstruir:
```bash
# 1. Compilar x264 (sin OpenCL, sin ASM para portabilidad)
cd /tmp && git clone https://code.videolan.org/videolan/x264.git
cd x264
./configure --host=aarch64-linux-android --sysroot=$SYSROOT \
  --enable-pic --enable-static --disable-cli --disable-asm --disable-opencl
make -j$(nproc) && make install prefix=/tmp/x264-install

# 2. Compilar FFmpeg
cd /tmp && wget https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz && tar xf ffmpeg-7.1.tar.xz
cd ffmpeg-7.1
./configure \
  --enable-cross-compile --target-os=android --arch=aarch64 --cpu=armv8-a \
  --cross-prefix=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm- \
  --cc=$NDK/.../aarch64-linux-android26-clang \
  ... (ver build completo en config de strings del binario)
make -j$(nproc)

# 3. Copiar binario a assets
cp ffmpeg app/src/main/assets/ffmpeg/arm64-v8a/ffmpeg
```

## Pipeline de comandos FFmpeg

### 1. Compresión
```
ffmpeg -y -i source.mp4 -c:v libx264 -preset fast -crf 28 \
  -vf "scale=w=1920:h=-2" -c:a aac -b:a 96k -movflags +faststart source_c.mp4
```

### 2. Extracción de audio (para transcripción)
```
ffmpeg -y -i source_c.mp4 -vn -ac 1 -ar 16000 -c:a pcm_s16le audio.wav
```

### 3. Segmentos (por cada rango del EDL)
```
ffmpeg -y -ss {start} -i source_c.mp4 -t {dur} \
  -vf "scale=w=1920:h=-2" \
  -af "afade=t=in:st=0:d=0.03,afade=t=out:st={fade_out}:d=0.03" \
  -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r 24 \
  -c:a aac -b:a 192k -ar 48000 -movflags +faststart seg_000.mp4
```

### 4. Concatenación (concat demuxer, sin re-encode)
```
ffmpeg -y -f concat -safe 0 -i list.txt -c copy base.mp4
```

### 5. Normalización de audio (loudnorm, -14 LUFS)
```
ffmpeg -y -i base.mp4 -c:v copy \
  -af "loudnorm=I=-14:TP=-1:LRA=11" \
  -c:a aac -b:a 192k -ar 48000 -movflags +faststart final.mp4
```

## API del servidor

Endpoints en `video.davidpurkiss.com`:

- `POST /api/transcribe` — Recibe audio WAV, devuelve transcripción word-level (ElevenLabs Scribe)
- `POST /api/edl` — Recibe packed transcript, devuelve EDL JSON con rangos de corte (DeepSeek)

Auth: header `X-API-Key`

## Requisitos

- Android 8.0+ (API 26)
- FFmpeg se extrae a `/data/local/tmp/` en el dispositivo (necesita permisos de ejecución)
- Conexión a internet para transcripción y EDL (el renderizado es offline)

## Licencia

GPL v2+ (por libx264)
