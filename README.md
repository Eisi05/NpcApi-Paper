[![](https://jitpack.io/v/Eisi05/NpcApi-Paper.svg)](https://jitpack.io/#Eisi05/NpcApi-Paper)

[NPC Plugin for PaperMC](https://modrinth.com/plugin/npc-plugin)

[NpcApi for SpigotMC](https://github.com/Eisi05/NpcApi-Spigot)

# NpcAPI

A powerful and easy-to-use NPC (Non-Player Character) API for Minecraft Paper plugins that allows you to create, manage, and customize NPCs with
advanced features.

## Wiki

See the in-repo wiki pages under:
[📚 Project Wiki](https://github.com/Eisi05/NpcApi-Paper/wiki)

## Features

- Create custom NPCs with ease
- Customize NPC appearance (skins, glowing effects, etc.)
- Handle click events and interactions
- Play animations and control NPC behavior
- Save and load NPCs persistently
- Show/hide NPCs for specific players
- Comprehensive NPC management system
- Advanced pathfinding with A* and Theta* algorithms
- Entity-specific navigation capabilities (climb, swim, jump, doors)
- AI Goals system for autonomous NPC behavior

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
    <version>3.6.2</version>
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
    compileOnly 'com.github.Eisi05:NpcApi-Paper:3.6.2'
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
	    <version>3.6.2</version>
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
    implementation 'com.github.Eisi05:NpcApi-Paper:3.6.2'
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
import de.eisi05.npc.api.objects.NpcName;
NPC npc = new NPC(location, NpcName.of(Component.text("Test")));

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
npc.setName(NpcName.of(Component.text("New Name")));
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

### Pathfinding

NpcAPI provides advanced pathfinding with A* and Theta* algorithms, along with entity-specific navigation capabilities.

#### Basic Pathfinding

```java
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.pathfinding.util.PathfindingUtils;

List<Location> waypoints = List.of(start, mid, end);

// Synchronous pathfinding (simple)
Path path = PathfindingUtils.findPath(
    waypoints,
    50_000,     // maxIterations
    true,       // allowDiagonalMovement
    null        // progressListener
);

// Asynchronous pathfinding (simple)
PathfindingUtils.findPathAsync(waypoints, 50_000, true, (progress) -> {
    // progress updates (0.0 to 1.0)
}).thenAccept(path -> {
    // use the path
});
```

#### Navigation Capabilities

```java
import de.eisi05.npc.api.pathfinding.movement.NavCapabilities;
import org.bukkit.entity.EntityType;

// Get capabilities for an entity type
NavCapabilities caps = NavCapabilities.of(EntityType.PLAYER);

// Check capabilities
boolean canClimb = caps.canClimb();           // Can use ladders/vines
boolean canSwim = caps.canSwim();             // Can swim in water
boolean canGapJump = caps.canGapJump();       // Can jump over gaps
double maxJumpHeight = caps.maxJumpHeight();  // Jump apex in blocks

// Register custom capabilities
NavCapabilities custom = NavCapabilities.DEFAULT
    .maxJumpHeight(3.0)
    .canClimbWalls(true);
NavCapabilities.register(EntityType.VILLAGER, custom);
```

### AI Goals

The AI Goals system allows NPCs to have autonomous behavior with configurable priorities.

#### Adding Goals

```java
import de.eisi05.npc.api.ai.goals.WalkToLocationGoal;
import de.eisi05.npc.api.ai.goals.WanderGoal;
import de.eisi05.npc.api.ai.goals.FollowEntityGoal;
import de.eisi05.npc.api.ai.goals.AttackEntityGoal;

// Walk to a specific location
WalkToLocationGoal walkGoal = new WalkToLocationGoal.Builder(targetLocation)
    .speed(0.4)
    .maxIterations(5000)
    .allowDiagonal(true)
    .build();
npc.addGoal(walkGoal);

// Wander randomly
WanderGoal wanderGoal = new WanderGoal(15, centerLocation);
npc.addGoal(wanderGoal);

// Follow an entity
FollowEntityGoal followGoal = new FollowEntityGoal(player.getUniqueId(), 5.0, 2.0, 0.5);
npc.addGoal(followGoal);

// Attack entities
AttackEntityGoal attackGoal = new AttackEntityGoal(
    entity -> entity instanceof Monster,
    15.0,  // attack range
    0.5    // movement speed
);
npc.addGoal(attackGoal);
```

#### Goal Priorities

Goals have their priority set in their constructor:
- `WalkToLocationGoal` - Priority.MEDIUM
- `WanderGoal` - Priority.MEDIUM
- `FollowEntityGoal` - Priority.ALWAYS
- `AttackEntityGoal` - Priority.ALWAYS

To create custom goals with specific priorities, extend the `Goal` class and pass the desired priority to the constructor:
```java
public class MyCustomGoal extends Goal {
    public MyCustomGoal() {
        super(Priority.HIGH); // Set priority in constructor
    }
    // ... implement goal methods
}
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
- Paper 1.21 - 26.3
- Minecraft server with NPC support
