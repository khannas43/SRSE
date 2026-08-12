import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async redirects() {
    return [
      {
        source: "/",
        destination: "/rules",
        permanent: false,
      },
    ];
  },
};

export default nextConfig;
