package com.uade.tpo.deportes.service.usuario;

import com.uade.tpo.deportes.config.JwtService;
import com.uade.tpo.deportes.dto.*;
import com.uade.tpo.deportes.entity.Usuario;
import com.uade.tpo.deportes.enums.Role;
import com.uade.tpo.deportes.exceptions.EmailInvalidoException;
import com.uade.tpo.deportes.exceptions.UsuarioNoEncontradoException;
import com.uade.tpo.deportes.exceptions.UsuarioYaExisteException;
import com.uade.tpo.deportes.repository.PartidoRepository;
import com.uade.tpo.deportes.repository.UsuarioRepository;
import com.uade.tpo.deportes.service.auth.EmailValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UsuarioServiceImpl implements UsuarioService {

    @Autowired
    private UsuarioRepository usuarioRepository;
    
    @Autowired
    private PartidoRepository partidoRepository;
    
    @Autowired
    private JwtService jwtService;
    
    @Autowired
    private AuthenticationManager authenticationManager;
    
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public RegisterResponse registrarUsuario(RegisterRequest request) {
        // 1. Validaciones de campos obligatorios
        if (request.getNombreUsuario() == null || request.getNombreUsuario().trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de usuario es obligatorio");
        }
        
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("El email es obligatorio");
        }
        
        if (request.getContrasena() == null || request.getContrasena().length() < 8) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres");
        }

        // 2. Validar formato de email
        if (!EmailValidator.esEmailValido(request.getEmail())) {
            throw new EmailInvalidoException();
        }

        // 3. Verificar si ya existe el usuario
        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new UsuarioYaExisteException("Ya existe un usuario con ese email");
        }
        
        if (usuarioRepository.existsByNombreUsuario(request.getNombreUsuario())) {
            throw new UsuarioYaExisteException("Ya existe un usuario con ese nombre de usuario");
        }

        // 4. Crear usuario
        Usuario usuario = Usuario.builder()
                .nombreUsuario(request.getNombreUsuario().trim())
                .email(request.getEmail().trim().toLowerCase())
                .contrasena(passwordEncoder.encode(request.getContrasena()))
                .deporteFavorito(request.getDeporteFavorito()) // Puede ser null
                .nivelJuego(request.getNivelJuego()) // Puede ser null
                .role(Role.JUGADOR) // Por defecto todos son jugadores
                .activo(true)
                .build();

        usuarioRepository.save(usuario);

        // 5. Generar token JWT
        String token = jwtService.generateToken(usuario);

        return RegisterResponse.builder()
                .token(token)
                .mensaje("¡Usuario registrado exitosamente!")
                .build();
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        // 1. Validaciones de campos obligatorios
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("El email es obligatorio");
        }
        
        if (request.getContrasena() == null || request.getContrasena().isEmpty()) {
            throw new IllegalArgumentException("La contraseña es obligatoria");
        }

        // 2. Validar formato de email
        if (!EmailValidator.esEmailValido(request.getEmail())) {
            throw new EmailInvalidoException();
        }

        // 3. Buscar usuario
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new UsuarioNoEncontradoException("Credenciales inválidas"));

        // 4. Verificar que esté activo
        if (!usuario.isActivo()) {
            throw new IllegalStateException("Usuario inactivo. Contacte al administrador.");
        }

        // 5. Autenticar con Spring Security
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail().trim().toLowerCase(),
                        request.getContrasena()
                )
        );

        // 6. Generar token JWT
        String token = jwtService.generateToken(usuario);

        return LoginResponse.builder()
                .token(token)
                .role(usuario.getRole().name())
                .mensaje("Login exitoso")
                .build();
    }

    @Override
    public UsuarioResponse obtenerPerfil(String email) {
        Usuario usuario = obtenerUsuarioPorEmail(email);
        return mapearAResponse(usuario);
    }

    @Override
    @Transactional
    public UsuarioResponse actualizarPerfil(String email, ActualizarPerfilRequest request) {
        Usuario usuario = obtenerUsuarioPorEmail(email);
        
        // Actualizar campos opcionales
        if (request.getDeporteFavorito() != null) {
            usuario.setDeporteFavorito(request.getDeporteFavorito());
        }
        if (request.getNivelJuego() != null) {
            usuario.setNivelJuego(request.getNivelJuego());
        }
        
        usuarioRepository.save(usuario);
        return mapearAResponse(usuario);
    }

    @Override
    public List<UsuarioResponse> buscarUsuarios(CriteriosBusquedaUsuario criterios) {
        // Implementación simplificada
        List<Usuario> usuarios = usuarioRepository.findAll();
        
        return usuarios.stream()
                .filter(u -> u.isActivo())
                .filter(u -> criterios.getDeporteFavorito() == null || 
                           u.getDeporteFavorito() == criterios.getDeporteFavorito())
                .filter(u -> criterios.getNivelJuego() == null || 
                           u.getNivelJuego() == criterios.getNivelJuego())
                .map(this::mapearAResponse)
                .collect(Collectors.toList());
    }

    @Override
    public Usuario obtenerUsuarioPorId(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new UsuarioNoEncontradoException("Usuario no encontrado con ID: " + id));
    }

    @Override
    public Usuario obtenerUsuarioPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new UsuarioNoEncontradoException("Usuario no encontrado con email: " + email));
    }

    @Override
    public EstadisticasUsuarioResponse obtenerEstadisticas(String email) {
        Usuario usuario = obtenerUsuarioPorEmail(email);
        
        // Contar partidos
        long partidosOrganizados = partidoRepository.findByOrganizador(usuario).size();
        long partidosJugados = partidoRepository.findPartidosConJugador(usuario).size();
        long partidosFinalizados = partidoRepository.findHistorialUsuario(usuario, "FINALIZADO").size();
        long partidosCancelados = partidoRepository.findHistorialUsuario(usuario, "CANCELADO").size();
        
        return EstadisticasUsuarioResponse.builder()
                .usuarioId(usuario.getId())
                .nombreUsuario(usuario.getNombreUsuario())
                .partidosJugados((int) partidosJugados)
                .partidosOrganizados((int) partidosOrganizados)
                .partidosFinalizados((int) partidosFinalizados)
                .partidosCancelados((int) partidosCancelados)
                .deporteFavorito(usuario.getDeporteFavorito() != null ? 
                    usuario.getDeporteFavorito().getNombre() : null)
                .build();
    }

    private UsuarioResponse mapearAResponse(Usuario usuario) {
        return UsuarioResponse.builder()
                .id(usuario.getId())
                .nombreUsuario(usuario.getNombreUsuario())
                .email(usuario.getEmail())
                .deporteFavorito(usuario.getDeporteFavorito())
                .nivelJuego(usuario.getNivelJuego())
                .role(usuario.getRole())
                .activo(usuario.isActivo())
                .createdAt(usuario.getCreatedAt())
                .build();
    }
}