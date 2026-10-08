import Foundation

/// Los videos que la persona ya abrió desde la app.
///
/// YouTube no le dice a ninguna app qué vio alguien ni en qué minuto se quedó,
/// así que lo único que se puede saber es esto: que tocó el video aquí y la
/// app la mandó a verlo. Por eso las tarjetas dicen "Ya lo abriste" y no "Ya
/// lo viste".
///
/// Se guarda en el teléfono y no en el servidor a propósito: qué videos abre
/// cada quien es justo el historial que no queremos tener. La contra es que
/// al cambiar de teléfono las marcas empiezan de cero.
///
/// Las vistas lo leen con `@AppStorage(Abiertos.clave)`, que se entera solo
/// cuando cambia; el valor es la lista de IDs, uno por línea, del más viejo
/// al más nuevo.
enum Abiertos {
    static let clave = "videos_abiertos"

    /// El feed solo enseña lo reciente; pasado este número se olvidan los más
    /// viejos para que la lista no crezca sin fin.
    private static let tope = 500

    /// Si ese video está en la lista guardada.
    static func contiene(_ guardado: String, _ videoId: String) -> Bool {
        !videoId.isEmpty && guardado.split(separator: "\n").contains { $0 == videoId }
    }

    /// Apunta que este video ya se abrió. Sin ID no hay nada que apuntar.
    static func marcar(_ videoId: String?) {
        guard let videoId, !videoId.isEmpty else { return }

        let guardado = UserDefaults.standard.string(forKey: clave) ?? ""
        var lista = guardado.split(separator: "\n").map(String.init)
        guard !lista.contains(videoId) else { return }

        lista.append(videoId)
        UserDefaults.standard.set(lista.suffix(tope).joined(separator: "\n"), forKey: clave)
    }

    /// Borra todas las marcas. Va con el borrado de la cuenta.
    static func olvidar() {
        UserDefaults.standard.removeObject(forKey: clave)
    }
}
