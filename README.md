# FLAC library Java
This is a fork of [https://github.com/nayuki/FLAC-library-Java](https://github.com/nayuki/FLAC-library-Java)

### Changes made in this fork
- switched to Gradle
- fixed javadocs in the decoder part
- reuse FrameInfo instances to offload the GC
- added a FlacLowLevelInput that takes an InputStream as input