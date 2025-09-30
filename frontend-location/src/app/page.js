'use client';

import dynamic from 'next/dynamic';

// Import LiveMap with no SSR to prevent hydration issues
const LiveMap = dynamic(() => import('@/components/LiveMap'), {
  ssr: false,
});

export default function Home() {
  return (
    <main>
      <LiveMap />
    </main>
  );
}