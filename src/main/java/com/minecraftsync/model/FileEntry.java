package com.minecraftsync.model;

/**
 * Un fichier d'un monde, identifié par son chemin relatif (séparateur « / »).
 *
 * @param path         chemin relatif au dossier du monde
 * @param size         taille en octets
 * @param lastModified date de modification locale (ms)
 * @param hash         empreinte SHA-256 en hexadécimal
 */
public record FileEntry(String path, long size, long lastModified, String hash) {
}
