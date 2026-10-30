import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  experimental: {
    // /api is proxied with a copy of the request body, cut at 10 MB by default: a Cin7 inventory list is ~40 MB
    proxyClientMaxBodySize: "100mb",
    // Large imports can take longer than the 30 s default
    proxyTimeout: 5 * 60 * 1000,
  },
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${process.env.BACKEND_URL ?? "http://localhost:8080"}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
