# المنصف في القدرات

أداة JavaFX تحوّل تصدير محادثة تليجرام (result.json من "Export chat history" في
Telegram Desktop) إلى عرض PowerPoint منظم وبدون أي أسماء أو صور شخصية.

- **مفيش تسجيل دخول أو كود تليفون** — بتشتغل على ملف تصدير Telegram Desktop نفسه.
- **مفيش اسم شخص ولا صورة بروفايل** في أي سلايد.
- رسائل متتالية من نفس الشخص بتتجمع؛ سؤال جديد (ترقيم، تغيّر شخص، فجوة وقت) بيبدأ سلايد جديد.
- أي `@username` بيتشال تلقائيًا، وكذلك رسائل التوقيع/التحويل الآلي.
- الرسائل المكررة حرفيًا بتتشال، ما عدا أول ظهور لها.
- المحتوى الطويل أوي بيتقسم على أكتر من سلايد بدل ما الخط يتصغّر جدًا.

## ⚠️ حدود الاختبار

منطق القراءة/التجميع/التنظيف/الحذف كامل ومُختبر فعليًا (نفس النتائج بالظبط
في كل مرة اتغيّر فيها الكود). لكن الشاشة (JavaFX) وبناء ملف PowerPoint
(Apache POI) متقدرش أُشغّلهم في بيئتي لأنها ممنوعة من الوصول لـ Maven Central.
لو طلعت مشكلة أول تشغيل، ابعتيلي نص الخطأ بالظبط.

## البنية المعمارية (Clean Architecture)

```
src/main/java/com/munsif/
├── MainApp.java                         ← واجهة JavaFX + composition root
│
├── domain/                              ← نماذج بيانات بحتة، صفر اعتماد على أي مكتبة
│   ├── TelegramMessage.java
│   ├── TextSegment.java
│   ├── TextRun.java
│   ├── ContentItem.java
│   └── MessageGroup.java
│
├── usecase/                             ← منطق العمل، صفر اعتماد على JavaFX/POI/org.json
│   ├── GeneratePresentationUseCase.java ← الحالة الاستخدامية الرئيسية (orchestration)
│   ├── TextProcessor.java               ← تنظيف النص، كشف رسائل التوقيع الآلي
│   ├── Grouper.java                     ← تجميع الرسائل + تقسيم السلايدات الكبيرة
│   ├── Deduplicator.java                ← حذف المكرر
│   └── port/                            ← الواجهات (Ports) اللي الـ use case بيعتمد عليها
│       ├── ExportReader.java
│       ├── PresentationExporter.java
│       └── LoadedExport.java
│
└── infrastructure/                      ← تفاصيل التنفيذ الفعلية (Adapters)
    ├── telegram/
    │   └── TelegramExportReader.java    ← ينفّذ ExportReader باستخدام org.json
    └── pptx/
        └── PptxPresentationWriter.java  ← ينفّذ PresentationExporter باستخدام Apache POI
```

**قاعدة الاعتماد (dependency rule):** الأسهم كلها بتتجه لجوه — `infrastructure`
بيعتمد على `usecase` (بينفّذ الـ ports بتاعته)، و`usecase` بيعتمد على `domain`
بس. لا `domain` ولا `usecase` عارفين حاجة عن JavaFX أو Apache POI أو org.json.
النتيجة العملية: لو حبيتي تستبدلي مصدر التصدير بحاجة تانية، أو تصدّري لصيغة
غير PowerPoint، أو تكتبي unit tests للمنطق، مش هتحتاجي تلمسي غير طبقة
الـ `infrastructure`.

## المتطلبات

- **JDK 21** — [adoptium.net](https://adoptium.net)
- **Maven** — [maven.apache.org](https://maven.apache.org/download.cgi)

```
java -version
mvn -version
```

## التشغيل أثناء التطوير

```
mvn clean javafx:run
```

أو دبل كليك على اختصار `run.bat` لو عملتيه على سطح المكتب (شوفي الأسفل).

## تشغيل سريع بدون سطر أوامر (run.bat)

فولدر المشروع فيه ملف `run.bat`. اعملي اختصار له على سطح المكتب (كليك يمين
عليه → Send to → Desktop) وسمّيه "المنصف في القدرات". من دلوقتي دبل كليك على
الاختصار بس، بدون فتح Command Prompt.

## بناء نسخة .exe حقيقية (بدون Maven ولا سطر أوامر بعد كده)

### الخطوة 1: بناء jar واحد فيه كل حاجة
```
mvn clean package
```
هيطلعلك `target/al-munsif-fi-al-qudurat.jar` — ملف واحد فيه التطبيق كامل
بالإضافة لـ JavaFX و Apache POI و org.json، جاهز يشتغل لوحده.

### الخطوة 2: jpackage
`jpackage` بييجي جاهز مع أي JDK 17+ (يعني عندك أصلًا، مش محتاجة تنزلي حاجة
زيادة). فولدر `target` فيه أكتر من jar (Maven بيسيب نسخة احتياطية باسم
`original-...jar`)، فعشان نتجنب أي تعارض، ننسخ الـ jar الصحيح لوحده لفولدر
نضيف الأول:

```
mkdir dist-input
copy target\al-munsif-fi-al-qudurat.jar dist-input\
```

بعدها:

```
jpackage --type app-image ^
  --input dist-input ^
  --dest dist ^
  --name "AlMunsifFiAlQudurat" ^
  --main-jar al-munsif-fi-al-qudurat.jar ^
  --main-class com.munsif.Launcher
```

النتيجة: فولدر `dist\AlMunsifFiAlQudurat\` فيه ملف
`AlMunsifFiAlQudurat.exe` — انقلي الفولدر ده كله لسطح المكتب (أو أي مكان)،
ودبل كليك على الـ .exe جواه. هيفتح على طول من غير أي Command Prompt، ومن
غير ما يحتاج الجهاز يكون فيه Java أصلًا — الـ runtime كامل متضمّن جوه الفولدر.

لو عايزة ملف تثبيت حقيقي (.msi) بدل فولدر، غيّري `--type app-image` لـ
`--type msi` — لكن ده محتاج تثبيت [WiX Toolset](https://wixtoolset.org/)
على جهازك الأول.

**ملاحظة:** الخطوة دي (jpackage) هي الحاجة الوحيدة في المشروع اللي متقدرش
أختبرها فعليًا عندي. لو طلعت مشكلة، ابعتيلي نص الخطأ بالظبط.

## لو عايزة تبيعيها لعملاء

كل عميل هيستخدم نفس المبدأ: يعمل تصدير لأي شات هو عضو فيه من Telegram Desktop
بتاعه، ويشغّل الأداة (أو ملف .exe الجاهز) على الفولدر الناتج. الأداة نفسها
مش بتلمس حساب حد ولا بتتجاوز أي حماية في تليجرام.

**نقطة تستاهل تفكير:** لو نويتي تحطي كود المشروع على GitHub كـ repository
عام (public)، أي حد هيقدر ياخده ويستخدمه أو يبيعه بنفسه، وده ممكن يتعارض مع
نيتك تبيعيه لعملاء. لو عايزة تحافظي على حقك التجاري، الأنسب يكون الـ repo
**خاص (private)** — تشاركيه مع نفسك بس أو مع أي حد تحددي، وتفضل ملكية الكود
والحق التجاري ليكي وحدك. لو عايزة الكود يبقى عام برضو، فكري في ترخيص غير
مفتوح تمامًا (زي "كل الحقوق محفوظة" بدل MIT/Apache) عشان تحافظي على حق البيع.

## بنية الفولدر الكاملة

```
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
