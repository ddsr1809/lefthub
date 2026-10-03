import SwiftUI

/// Lo primero que se ve al instalar la app.
///
/// Hasta que la persona toca "Aceptar y continuar" no se crea ninguna cuenta.
/// Lo piden las políticas de la API de YouTube (aceptar la política de
/// privacidad antes de usar la app) y la ley mexicana de datos personales: los
/// creadores que alguien sigue pueden revelar sus opiniones políticas, y ese
/// dato necesita consentimiento expreso.
///
/// No hay botón de rechazar: quien no está de acuerdo cierra la app.
struct BienvenidaVista: View {
    let onAceptar: () -> Void

    @Environment(\.paleta) private var paleta

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                TextoRelay("Voces de Izquierda", estilo: .titulo)
                    .padding(.top, Espacio.lg)
                    .padding(.bottom, Espacio.md)

                TextoRelay(
                    "Te avisa cuando publican los creadores que sigues y te lleva al video en su app oficial.",
                    estilo: .cuerpo,
                    color: paleta.textoSuave
                )
                .padding(.bottom, Espacio.lg)

                Divider().background(paleta.borde)
                TextoRelay("Antes de empezar", estilo: .seccion)
                    .padding(.top, Espacio.lg)
                    .padding(.bottom, Espacio.md)

                punto("Para funcionar guardamos a qué creadores sigues, tus ajustes y los datos de tu conexión, como tu dirección IP.")
                punto("Los creadores que sigues pueden dar a entender tus opiniones políticas. Solo usamos ese dato para mostrarte sus novedades y enviarte sus avisos. No lo compartimos.")
                punto("No hace falta dar tu nombre ni tu correo, y puedes borrar tu cuenta y tus datos cuando quieras desde Ajustes.")

                BotonGrande(
                    titulo: "Política de privacidad",
                    subtitulo: "Se abre en Safari",
                    variante: .secundario
                ) {
                    Enrutador.abrirPagina(Enrutador.urlPrivacidad)
                }
                .padding(.top, Espacio.sm)

                BotonGrande(
                    titulo: "Condiciones de servicio",
                    subtitulo: "Se abren en Safari",
                    variante: .secundario
                ) {
                    Enrutador.abrirPagina(Enrutador.urlCondiciones)
                }

                TextoRelay(
                    "Al tocar «Aceptar y continuar» aceptas la política de privacidad, las condiciones de servicio y las Condiciones del Servicio de YouTube, y consientes que guardemos los creadores que sigas.",
                    estilo: .cuerpo
                )
                .padding(.vertical, Espacio.md)

                BotonGrande(titulo: "Aceptar y continuar") {
                    onAceptar()
                }

                TextoRelay(
                    "Si no estás de acuerdo, cierra la app. No se crea ninguna cuenta.",
                    estilo: .secundario
                )
                .padding(.bottom, Espacio.xxl)
            }
            .padding(Espacio.md)
        }
        .background(paleta.fondo)
    }

    private func punto(_ texto: String) -> some View {
        TextoRelay(texto, estilo: .cuerpo, color: paleta.textoSuave)
            .padding(.bottom, Espacio.md)
    }
}
