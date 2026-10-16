import type { NextConfig } from "next";
import path from "path";

const nextConfig: NextConfig = {
  transpilePackages: ["framer-motion"],
  webpack: (config) => {
    const rootDir = process.cwd();
    config.resolve.alias = {
      ...config.resolve.alias,
      "firebase/auth": path.resolve(rootDir, "node_modules/firebase/auth/dist/index.cjs.js"),
      "firebase/app": path.resolve(rootDir, "node_modules/firebase/app/dist/index.cjs.js"),
      "framer-motion": path.resolve(rootDir, "node_modules/framer-motion/dist/cjs/index.js"),
    };
    return config;
  },
};

export default nextConfig;
