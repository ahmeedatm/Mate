# Mot de réveil (Vosk) : JNA retrouve les classes et les méthodes natives par leur nom, R8 ne doit ni les renommer ni
# les retirer. Règles de la démo Android de Vosk.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
# Parties de JNA pour le bureau (AWT), jamais chargées sur Android.
-dontwarn java.awt.**
