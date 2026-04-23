# Mineway Plugin / Mod

รองรับทุก Java Minecraft server platform ในโปรเจกต์เดียว

## โครงสร้าง

```
mct-plugin/
├── mct-core/        ← logic ทั้งหมด (Java 8+, ไม่ขึ้นกับ platform)
├── mct-bukkit/      ← Bukkit / Spigot / Paper / Purpur / Folia
├── mct-proxy/       ← BungeeCord / Waterfall / Velocity
├── mct-fabric/      ← Fabric / Quilt  (Java 17+)
└── mct-forge/       ← Forge / NeoForge (Java 17+)
```

## Build

ต้องมี JDK 17+ และ Gradle

```bash
# Build ทุก platform พร้อมกัน
./gradlew build

# Build แค่ platform ที่ต้องการ
./gradlew :mineway-bukkit:build
./gradlew :mineway-proxy:build
./gradlew :mineway-fabric:build
./gradlew :mineway-forge:build
```

ไฟล์ .jar อยู่ที่ `<module>/build/libs/`

## ติดตั้ง

### Bukkit / Spigot / Paper / Purpur / Folia
```
วาง Mineway-Bukkit-1.0.0.jar ไว้ใน plugins/
รีสตาร์ทเซิร์ฟ
แก้ไข plugins/Mineway/config.yml
ใส่ api_key แล้วรัน /mineway reload
```

### BungeeCord / Waterfall
```
วาง Mineway-Proxy-1.0.0.jar ไว้ใน plugins/
รีสตาร์ท proxy
แก้ไข plugins/Mineway/config.yml
```

### Velocity
```
วาง Mineway-Proxy-1.0.0.jar ไว้ใน plugins/
รีสตาร์ท proxy
แก้ไข plugins/mineway/config.yml
```

### Fabric / Quilt
```
วาง Mineway-Fabric-1.0.0.jar ไว้ใน mods/
รีสตาร์ทเซิร์ฟ
แก้ไข config/mineway/config.yml
```

### Forge / NeoForge
```
วาง Mineway-Forge-1.0.0.jar ไว้ใน mods/
รีสตาร์ทเซิร์ฟ
แก้ไข config/mineway/config.yml
```

## config.yml

```yaml
# API Key จาก https://mineway.cloud/dashboard
api_key: "mw_live_xxxxxxxxxxxxxxxx"

# Tunnel Server (ไม่ต้องแก้)
server_host: "tunnel.mineway.cloud"
server_port: 8765

# Auto reconnect ถ้าหลุด
auto_reconnect: true
reconnect_delay: 5

# Debug log
debug: false
```

## Commands (ทุก platform)

| คำสั่ง | ผล |
|---|---|
| `/mineway` หรือ `/mw` | แสดงสถานะ |
| `/mineway status` | สถานะ + hostname |
| `/mineway reload` | reload config + reconnect |
| `/mineway stop` | หยุด tunnel |
| `/mineway start` | เริ่ม tunnel |

Permission: `mineway.admin` (default: op)

## Protocol กับ Tunnel Server

Plugin เลิกใช้ WebSocket แล้วเพื่อลด Overhead (ไม่มี JSON, ไม่มี Base64, ไม่มี HTTP overhead)
Plugin จะเชื่อมต่อผ่าน **Raw TCP Socket** → ส่ง `AUTH` frame แบบไบนารี → รับ `AUTH_OK` แบบไบนารี → tunnel พร้อมทำงาน และส่งข้อมูลเกมเป็นไบนารีโดยตรงทำให้ปิงลดลงมาก

## Bedrock (GeyserMC)

ติดตั้ง GeyserMC บน server เดียวกัน GeyserMC จะ handle Bedrock players แล้วส่ง traffic เข้า MC server ผ่าน TCP ปกติ ไม่ต้องทำอะไรพิเศษ

## NeoForge

ใช้ jar เดียวกับ Forge ได้เลย NeoForge ใช้ API เดียวกัน แค่เปลี่ยน dependency ใน build.gradle:
```groovy
implementation "net.neoforged:neoforge:20.4.237"
```
