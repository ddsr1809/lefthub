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

# Credential Manager (entrar con Google). R8 quita en release las clases que
# lo conectan con Google Play Services porque nada las nombra directamente, y
# entonces el inicio de sesion falla solo en la version de produccion. Es la
# regla que indica la documentacion de Android para Credential Manager.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
