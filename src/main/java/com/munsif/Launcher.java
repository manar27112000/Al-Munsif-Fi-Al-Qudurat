package com.munsif;

/**
 * لما JavaFX بيتحط مع باقي المكتبات في jar واحد ويتشغّل بـ "java -jar" مباشرة،
 * جافا بترفض تشغّل أي كلاس بيرث Application مباشرة (بتفتكر إن JavaFX مش
 * متاح كـ module حقيقي حتى لو الكلاسات موجودة فعليًا). الحل المعروف: كلاس
 * launcher بسيط زي ده، مش وارث من Application، وبيكون هو الـ Main-Class
 * بدل MainApp — وده بيخلي جافا تتخطى الفحص وتشغّل التطبيق عادي.
 */
public class Launcher {
    public static void main(String[] args) {
        MainApp.main(args);
    }
}
