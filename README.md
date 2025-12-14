[![](https://jitpack.io/v/Eisi05/NpcApi-Paper.svg)](https://jitpack.io/#Eisi05/NpcApi-Paper)

[NPC Plugin for PaperMC](https://modrinth.com/plugin/npc-plugin)

[NpcApi for SpigotMC](https://github.com/Eisi05/NpcApi-Spigot)

# NpcAPI

A powerful and easy-to-use NPC (Non-Player Character) API for Minecraft Spigot plugins that allows you to create, manage, and customize NPCs with
advanced features.

## Wiki

See the in-repo wiki pages under:
[📚 Project Wiki](https://github.com/Eisi05/NpcApi-Paper/wiki)

## Features

- 🎭 Create custom NPCs with ease
- 🎨 Customize NPC appearance (skins, glowing effects, etc.)
- 👆 Handle click events and interactions
- 🎬 Play animations and control NPC behavior
- 💾 Save and load NPCs persistently
- 👥 Show/hide NPCs for specific players
- 🔍 Comprehensive NPC management system

## Installation
Choose your preferred installation method based on your project needs:

### Method 1: Plugin Dependency (Recommended)

This method requires [NpcPlugin-Paper](https://modrinth.com/plugin/npc-plugin?loader=paper#download) to be installed as a separate plugin on the server.

#### Maven
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.Eisi05</groupId>
    <artifactId>NpcApi-Paper</artifactId>
    <version>1.21.x-16</version>
    <scope>provided</scope>
</dependency>
```

#### Gradle
```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    compileOnly 'com.github.Eisi05:NpcApi-Paper:1.21.x-16'
}
```

#### Plugin Configuration
Add NpcPlugin-Paper as a dependency in your `plugin.yml`:
```yaml
# Required dependency (hard dependency)
dependencies:
  server:
    - name: NpcPlugin-Paper
      required: true

# Or optional dependency (soft dependency)
dependencies:
  server:
    - name: NpcPlugin-Paper
      required: false
```
---

### Method 2: Shaded Dependency

This method bundles NpcApi directly into your plugin JAR file.

#### Maven
Add the repository and dependency to your `pom.xml`:
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
   	<dependency>
	    <groupId>com.github.Eisi05</groupId>
	    <artifactId>NpcApi-Paper</artifactId>
	    <version>1.21.x-16</version>
	</dependency>
</dependencies>
```

#### Gradle
Add the following to your `build.gradle`:
```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.Eisi05:NpcApi-Paper:1.21.x-16'
}
```

#### Plugin Configuration
To enabled/disable the NpcApi add this to your Plugin Main class:
```java

@Override
public void onEnable()
{
    // Initialize NpcAPI with default configuration
    NpcApi.createInstance(this, new NpcConfig());
}

@Override
public void onDisable()
{
    // Properly disable NpcAPI
    NpcApi.disable();
}
```
---

## Usage Examples

### Creating a Basic NPC

```java
// Create a location where the NPC should spawn
Location location = new Location(world, x, y, z);

// Create a new NPC with a name
NPC npc = new NPC(location, Component.text("Test"));

// Enable the NPC to make it visible to all players
npc.setEnabled(true);
```

### Customizing NPC Appearance

```java
// Make the NPC glow with a red color
npc.setOption(NpcOption.GLOWING, ChatFormat.RED);

// Set a custom skin from a player
npc.setOption(NpcOption.SKIN, NpcSkin.of(Skin.fromPlayer(player)));
```

### Handling Click Events

```java
// Set up a click event handler
npc.setClickEvent(event -> {
    Player player = event.getPlayer();
    NPC clickedNpc = event.getNpc();
    player.sendMessage(Component.text("You clicked ").append(clickedNpc.getName()));
});
```

### Managing NPC State

```java
npc.save();
npc.setName(Component.text("New Name"));
npc.setLocation(newLocation);
npc.reload();
```

### Advanced NPC Control

```java
npc.playAnimation(/* animation parameters */);
npc.showNPCToPlayer(player);
npc.hideNpcFromPlayer(player);
npc.lookAtPlayer(player);
npc.delete();
```

## NPC Management

### Getting All NPCs

```java
// Get a list of all available NPCs
List<NPC> allNpcs = NpcManager.getList();
```

### Finding NPCs by UUID

```java
// Get a specific NPC by its UUID
UUID npcUuid = /* your NPC's UUID */;
NPC npc = NpcManager.fromUUID(npcUuid);
```

## API Reference

### NPC Class

| Method                                | Description                              |
|---------------------------------------|------------------------------------------|
| `setOption(NpcOption, Object)`        | Set NPC options like glowing, skin, etc. |
| `setClickEvent(Consumer<ClickEvent>)` | Set the click event handler              |
| `setEnabled(boolean)`                 | Enable/disable NPC visibility            |
| `save()`                              | Save NPC to persistent storage           |
| `reload()`                            | Reload NPC data                          |
| `setName(Component)`                  | Update NPC display name                  |
| `setLocation(Location)`               | Move NPC to new location                 |
| `playAnimation(...)`                  | Play NPC animation                       |
| `showNPCToPlayer(Player)`             | Show NPC to specific player              |
| `hideNpcFromPlayer(Player)`           | Hide NPC from specific player            |
| `lookAtPlayer(Player)`                | Make NPC look at player                  |
| `delete()`                            | Remove NPC permanently                   |
| `walkTo(Path, double, boolean, Consumer<Result>, players)` | Let the NPC walk along a path (can be created with PathfindingUtils class)  |

## Requirements

- Java 21+
- Paper 1.21 - 1.21.10
- Minecraft server with NPC support
