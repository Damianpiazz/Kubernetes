/** @type {import('next').NextConfig} */
const nextConfig = {
  // Imagen standalone para Docker (ver frontend/Dockerfile): el runtime queda
  // en .next/standalone con los node_modules trazados, en vez de la app entera.
  output: 'standalone',
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
}

export default nextConfig
