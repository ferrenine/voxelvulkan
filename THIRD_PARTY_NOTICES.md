# Third-Party Notices

Voxel bundles the following libraries inside the mod jar. They are not covered
by the project's LGPL-3.0 license; each remains under its own license.

| Component | Version | License | Purpose |
| --- | --- | --- | --- |
| RocksDB JNI (`org.rocksdb:rocksdbjni`) | 10.2.1 | Apache-2.0 / GPL-2.0 | Default section storage backend |
| XZ for Java (`org.tukaani:xz`) | 1.10 | Public Domain | Distant Horizons database import |
| SQLite JDBC (`org.xerial:sqlite-jdbc`) | 3.49.1.0 | Apache-2.0 | Distant Horizons database import |
| Jedis (`redis.clients:jedis`) | 5.1.0 | MIT | Optional Redis storage backend |
| Apache Commons Pool (`org.apache.commons:commons-pool2`) | 2.12.0 | Apache-2.0 | Jedis connection pooling |
| LWJGL LMDB / Zstandard bindings (`org.lwjgl:lwjgl-lmdb`, `org.lwjgl:lwjgl-zstd`) | 3.3.3 | BSD-3-Clause | Optional storage/compression backends |
| LMDB | bundled with LWJGL | OpenLDAP Public License | LMDB storage backend |
| Zstandard | bundled with LWJGL | BSD-3-Clause | ZSTD compression backend |

The full license texts are shipped inside the corresponding nested jars under
`META-INF/`. The published jar uses desktop-only repacks of RocksDB and SQLite
JDBC to keep the download small; the complete artifacts are used on the
development classpath. Minecraft, Fabric Loader, Fabric API, VulkanMod, Beryl
and Mod Menu are runtime dependencies and are not bundled by this project.
