# Firestore usa reflexion para mapear documentos a data classes.
# Sin esto, la version de release devuelve objetos vacios y cuesta horas dar
# con el motivo, porque en debug funciona perfectamente.
-keepclassmembers class com.vocesdeizquierda.lefthub.data.** {
    <init>();
    <fields>;
}
-keepnames class com.vocesdeizquierda.lefthub.data.**

-keepattributes Signature
-keepattributes *Annotation*
