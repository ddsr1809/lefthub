package com.tuempresa.relay.directorio;

import com.tuempresa.relay.modelo.Conexion;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.websub.WebSubService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guardado de un creador, compartido por los dos caminos por los que entra:
 * el panel (AdminController) y la copia que manda producción a testing
 * (ReplicaService). Así un campo nuevo se guarda igual por los dos.
 */
@Service
public class CreadoresService {

    private final Repositorios.Creadores creadores;
    private final WebSubService websub;

    public CreadoresService(Repositorios.Creadores creadores, WebSubService websub) {
        this.creadores = creadores;
        this.websub = websub;
    }

    /**
     * Vuelca la petición sobre el creador y lo guarda.
     *
     * @return el canal de YouTube que tenía antes, o null. Hace falta para
     *         darlo de baja en el hub si cambió.
     */
    @Transactional
    public String aplicar(Creador creador, Dtos.GuardarCreador peticion) {
        if (!Dtos.CATEGORIAS.contains(peticion.categoriaOtros())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Categoría no válida: " + peticion.categoriaOtros());
        }

        String canalPrevio = creador.getCanalDeYouTube();

        creador.setNombre(peticion.nombre().trim());
        creador.setCategoria(peticion.categoriaOtros());
        creador.setBio(recortar(peticion.bio(), 600));
        creador.setFotoUrl(peticion.fotoUrl());
        creador.setActivo(peticion.estaActivo());
        creador.setActualizadoEn(Instant.now());

        Map<String, Conexion> conexiones = new LinkedHashMap<>();
        peticion.conexionesSeguras().forEach((plataforma, dto) -> {
            if (!Dtos.PLATAFORMAS.contains(plataforma)) return;
            if (dto == null || dto.url() == null || dto.url().isBlank()) return;

            conexiones.put(plataforma, new Conexion(
                    dto.url().trim(),
                    dto.handle() != null ? dto.handle().trim() : null,
                    dto.channelId() != null ? dto.channelId().trim() : null));
        });
        creador.setConexiones(conexiones);

        creadores.saveAndFlush(creador);
        return canalPrevio;
    }

    /**
     * Deja el hub de acuerdo con lo guardado: baja del canal anterior si
     * cambió, y alta o baja del actual según el creador esté activo.
     *
     * Lanza si el hub falla. El creador ya quedó guardado; la renovación
     * programada vuelve a intentarlo en el siguiente ciclo.
     */
    public void sincronizarWebSub(String canalPrevio, String canalNuevo, boolean activo) {
        if (canalPrevio != null && !canalPrevio.equals(canalNuevo)) {
            websub.desuscribir(canalPrevio);
        }
        if (canalNuevo != null && !canalNuevo.isBlank()) {
            if (activo) websub.suscribir(canalNuevo);
            else websub.desuscribir(canalNuevo);
        }
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
