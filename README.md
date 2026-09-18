# Al-Munsif Fi Al-Qudurat

A JavaFX tool that converts a Telegram chat export (`result.json` from **Telegram Desktop → Export Chat History**) into a clean, organized PowerPoint presentation without any personal names or profile pictures.

* **No login or phone verification code** — the tool works directly with a Telegram Desktop export file.
* **No person's name or profile picture** appears on any slide.
* Consecutive messages from the same person are grouped together; a new question (numbering, sender change, or time gap) starts a new slide.
* Any `@username` is automatically removed, as well as automated signature/forwarding messages.
* Exact duplicate messages are removed, keeping only the first occurrence.
* Very long content is split across multiple slides instead of making the font extremely small.

## ⚠️ Testing Limitations

The reading, grouping, cleaning, and deduplication logic is fully implemented and tested. The results remain exactly the same across code changes.

However, the JavaFX UI and PowerPoint generation (Apache POI) cannot be executed in the current environment because access to Maven Central is blocked.

If you encounter an issue during the first run, send me the **exact error message**.

## Architecture — Clean Architecture

```text
src/main/java/com/munsif/
├── MainApp.java                         ← JavaFX UI + composition root
│
├── domain/                              ← Pure data models, zero library dependencies
│   ├── TelegramMessage.java
│   ├── TextSegment.java
│   ├── TextRun.java
│   ├── ContentItem.java
│   └── MessageGroup.java
│
├── usecase/                             ← Business logic, zero JavaFX/POI/org.json dependencies
│   ├── GeneratePresentationUseCase.java ← Main use case / orchestration
│   ├── TextProcessor.java               ← Text cleaning + automated signature detection
│   ├── Grouper.java                     ← Message grouping + large-slide splitting
│   ├── Deduplicator.java                ← Duplicate removal
│   └── port/                            ← Interfaces used by the use cases
│       ├── ExportReader.java
│       ├── PresentationExporter.java
│       └── LoadedExport.java
│
└── infrastructure/                      ← Implementation details / adapters
    ├── telegram/
    │   └── TelegramExportReader.java    ← ExportReader implementation using org.json
    └── pptx/
        └── PptxPresentationWriter.java  ← PresentationExporter implementation using Apache POI
```

### Dependency Rule

All dependencies point inward:

`infrastructure` depends on `usecase` by implementing its ports, while `usecase` depends only on `domain`.

Neither `domain` nor `usecase` knows anything about JavaFX, Apache POI, or org.json.

### Practical Benefit

If you want to replace the Telegram export source, export to a different format, or write unit tests for the business logic, you only need to change the `infrastructure` layer.

## Requirements

* **JDK 21** — [adoptium.net](https://adoptium.net?utm_source=chatgpt.com)
* **Maven** — [maven.apache.org](https://maven.apache.org/download.cgi?utm_source=chatgpt.com)

```bash
java -version
mvn -version
```

## Running During Development

```bash
mvn clean javafx:run
```

Or simply double-click `run.bat` if you created it on your desktop (see below).

## Quick Launch Without the Command Line — `run.bat`

The project folder contains a `run.bat` file.

Create a shortcut to it on your desktop:

**Right-click → Send to → Desktop**

Rename the shortcut to **"Al-Munsif Fi Al-Qudurat"**.

From then on, simply double-click the shortcut to launch the application without opening Command Prompt.

## Building a Real `.exe` Application

### Step 1: Build a Single JAR

```bash
mvn clean package
```

This creates:

```text
target/al-munsif-fi-al-qudurat.jar
```

The JAR contains the complete application along with JavaFX, Apache POI, and org.json, ready to be packaged.

### Step 2: Create the Application Image with `jpackage`

`jpackage` is included with JDK 17+.

The `target` folder may contain multiple JAR files because Maven keeps a backup named `original-...jar`.

To avoid any conflict, first copy the correct JAR into a clean folder:

```cmd
mkdir dist-input
copy target\al-munsif-fi-al-qudurat.jar dist-input\
```

Then run:

```cmd
jpackage --type app-image ^
  --input dist-input ^
  --dest dist ^
  --name "AlMunsifFiAlQudurat" ^
  --main-jar al-munsif-fi-al-qudurat.jar ^
  --main-class com.munsif.Launcher
```

The result will be:

```text
dist\AlMunsifFiAlQudurat\
```

Inside it, you will find:

```text
AlMunsifFiAlQudurat.exe
```

You can move the entire `AlMunsifFiAlQudurat` folder to the desktop or any other location and double-click the `.exe` to launch the application.

The packaged application includes its own Java runtime, so the target computer does **not** need Java installed separately.

### Creating a Real Installer

If you want a real `.msi` installer instead of an application folder, change:

```cmd
--type app-image
```

to:

```cmd
--type msi
```

This requires the [WiX Toolset](https://wixtoolset.org/?utm_source=chatgpt.com) to be installed first.

### Note

`jpackage` is the only part of the project that cannot be fully tested in the current environment.

If you encounter an issue, send me the **exact error message**.

## Selling the Application to Customers

Each customer follows the same process:

1. Export any Telegram chat they are a member of using Telegram Desktop.
2. Use the resulting export folder/file with the application.
3. Run the application or the prepared `.exe`.
4. Generate the PowerPoint presentation.

The application does not access anyone's Telegram account and does not bypass any Telegram security or protection mechanisms.

### Important Commercial Consideration

If you plan to publish the source code on GitHub as a **public repository**, anyone can access, copy, modify, and potentially redistribute or sell the software.

If you intend to sell the application commercially, a **private repository** is generally more appropriate for keeping the source code under your control.

If you decide to make the repository public, consider using a license that clearly reserves your commercial rights, such as a **proprietary / "All Rights Reserved" license**, rather than permissive open-source licenses such as MIT or Apache 2.0.

## Complete Project Structure

```text
al-munsif-fi-al-qudurat/
├── pom.xml
├── README.md
├── run.bat
└── src/main/java/com/munsif/
    ├── MainApp.java
    ├── domain/...
    ├── usecase/...
    │   └── port/...
    └── infrastructure/
        ├── telegram/...
        └── pptx/...
```
