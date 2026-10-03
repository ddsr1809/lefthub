import Foundation

/// Si la persona ya aceptó la política de privacidad y las condiciones.
///
/// Se guarda en el teléfono y no en el servidor a propósito: la pregunta se
/// hace antes de crear la cuenta, cuando todavía no hay a quién apuntárselo.
///
/// `version` es la fecha de los documentos publicados en vocesdeizquierda.com.
/// Si cambian de forma importante, pon aquí la fecha nueva y la app volverá a
/// pedir la aceptación a todo el mundo.
enum Aceptacion {
    static let version = "2026-10-02"
    static let clave = "legal_version_aceptada"
}
