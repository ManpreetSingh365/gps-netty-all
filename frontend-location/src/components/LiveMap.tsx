"use client";

import React, { useState, useEffect, useRef, useMemo } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

// Global flag to prevent multiple map initializations
let mapInstance: L.Map | null = null;

// TypeScript interface for location data
interface Location {
  latitude: number;
  longitude: number;
  altitude: number;
  speed: number;
  course: number;
  valid: boolean;
  timestamp: string;
  satellites: number;
}

// Custom marker icon
const createCustomIcon = () => {
  return new L.Icon({
    iconUrl:
      "https://png.pngtree.com/png-clipart/20250124/original/pngtree-colorful-delivery-truck-icon-png-image_20329840.png",
    shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
    iconSize: [50, 50],
    iconAnchor: [12, 41],
    popupAnchor: [1, -34],
    shadowSize: [41, 41],
  });
};

// Wrapper component to ensure map is only created once
const MapWrapper: React.FC<{
  currentLocation: Location | null;
  locationHistory: [number, number][];
  defaultCenter: [number, number];
  currentCenter: [number, number];
}> = ({ currentLocation, locationHistory, defaultCenter, currentCenter }) => {
  const mapRef = useRef<L.Map | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Keep references to marker & polyline so we update them in-place
  const markerRef = useRef<L.Marker | null>(null);
  const polylineRef = useRef<L.Polyline | null>(null);

  // Initialize map once on mount; cleanup on unmount only
  useEffect(() => {
    if (containerRef.current && !mapInstance) {
      console.log("Initializing map on container:", containerRef.current);
      mapInstance = L.map(containerRef.current).setView(defaultCenter, 13);
      mapRef.current = mapInstance;

      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      }).addTo(mapInstance);

      console.log("Map initialized successfully");
    }

    // cleanup only when this component unmounts
    return () => {
      if (mapInstance) {
        console.log("Cleaning up map instance");
        mapInstance.remove();
        mapInstance = null;
      }
    };
    // intentionally run only once on mount/unmount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Update map center when currentCenter changes (memoized upstream)
  useEffect(() => {
    if (
      mapInstance &&
      currentCenter &&
      currentCenter[0] !== 0 &&
      currentCenter[1] !== 0
    ) {
      // only set view if coordinates have meaning
      console.log("Updating map center to:", currentCenter);
      mapInstance.setView(currentCenter, mapInstance.getZoom());
    }
  }, [currentCenter]);

  // Add/update marker in place (no remove/add loops)
  useEffect(() => {
    if (!mapInstance) return;
    if (!currentLocation) return;

    const latlng: L.LatLngExpression = [
      currentLocation.latitude,
      currentLocation.longitude,
    ];

    if (!markerRef.current) {
      markerRef.current = L.marker(latlng, { icon: createCustomIcon() }).addTo(
        mapInstance
      );
    } else {
      markerRef.current.setLatLng(latlng);
    }
  }, [currentLocation]);

  // Add/update polyline in place (append / setLatLngs)
  useEffect(() => {
    if (!mapInstance) return;
    if (!locationHistory || locationHistory.length === 0) return;

    if (!polylineRef.current) {
      polylineRef.current = L.polyline(locationHistory, {
        color: "#3b82f6",
        weight: 3,
        opacity: 0.8,
      }).addTo(mapInstance);
    } else {
      polylineRef.current.setLatLngs(locationHistory);
    }
  }, [locationHistory]);

  return <div ref={containerRef} className="w-full h-full" />;
};

const LiveMap: React.FC = () => {
  const [currentLocation, setCurrentLocation] = useState<Location | null>(null);
  const [locationHistory, setLocationHistory] = useState<[number, number][]>(
    []
  );
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);

  // Memoize default center so reference stays stable
  // defaultCenter (already fine but keep tuple explicit)
  const defaultCenter = useMemo<[number, number]>(() => [40.7128, -74.006], []);

  // Memoize current center to avoid creating new array each render
  // currentCenter (explicit tuple type)
  const currentCenter = useMemo<[number, number]>(() => {
    return currentLocation
      ? [currentLocation.latitude, currentLocation.longitude]
      : defaultCenter;
  }, [currentLocation, defaultCenter]);
  useEffect(() => {
    console.log("Attempting to create SSE connection...");

    // encapsulate creation so we can reconnect cleanly
    const createEventSource = () => {
      // close any previous instance first
      if (eventSourceRef.current) {
        try {
          eventSourceRef.current.close();
        } catch (e) {
          /* ignore */
        }
      }

      const es = new EventSource("/api/locations/stream");
      eventSourceRef.current = es;

      es.onopen = () => {
        setIsConnected(true);
        console.log("SSE connection opened successfully");
      };

      es.onmessage = (event) => {
        try {
          // NOTE: backend may send non-location control messages; guard parsing
          console.log("Received SSE data:", event.data);
          const parsed = JSON.parse(event.data) as Location;

          if (parsed && parsed.valid && parsed.latitude && parsed.longitude) {
            // update current location
            setCurrentLocation((prev) => {
              // optional: avoid duplicate identical updates (tiny optimization)
              if (
                prev &&
                prev.latitude === parsed.latitude &&
                prev.longitude === parsed.longitude &&
                prev.timestamp === parsed.timestamp
              ) {
                return prev;
              }
              return parsed;
            });

            // update history (keep last 100)
            setLocationHistory((prev) => {
              const newHistory = [
                ...prev,
                [parsed.latitude, parsed.longitude] as [number, number],
              ];
              return newHistory.slice(-100);
            });
          } else {
            console.log("Invalid or control SSE data:", parsed);
          }
        } catch (error) {
          console.error(
            "Error parsing location data:",
            error,
            "Raw data:",
            event.data
          );
        }
      };

      es.onerror = (error) => {
        console.error("SSE connection error:", error);
        setIsConnected(false);

        // close this EventSource and schedule reconnect
        try {
          es.close();
        } catch (e) {
          /* ignore */
        }

        if (reconnectTimerRef.current) {
          clearTimeout(reconnectTimerRef.current);
          reconnectTimerRef.current = null;
        }
        // attempt reconnect after 5s
        reconnectTimerRef.current = window.setTimeout(() => {
          console.log("Reconnecting SSE...");
          createEventSource();
        }, 5000);
      };
    };

    // start it
    createEventSource();

    // cleanup on unmount
    return () => {
      if (eventSourceRef.current) {
        try {
          eventSourceRef.current.close();
        } catch (e) {
          /* ignore */
        }
        eventSourceRef.current = null;
      }
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      setIsConnected(false);
    };
  }, []); // run once

  return (
    <div className="w-full h-screen relative">
      {/* Connection status indicator */}
      <div className="absolute top-4 left-4 z-[1000] bg-white px-3 py-2 rounded-md shadow-md">
        <div className="flex items-center gap-2">
          <div
            className={`w-3 h-3 rounded-full ${
              isConnected ? "bg-green-500" : "bg-red-500"
            }`}
          />
          <span className="text-sm font-medium">
            {isConnected ? "Connected" : "Disconnected"}
          </span>
        </div>
      </div>

      {/* Location info panel */}
      {currentLocation && (
        <div className="absolute top-4 right-4 z-[1000] bg-white p-4 rounded-md shadow-md max-w-xs">
          <h3 className="font-bold text-sm mb-2">Current Location</h3>
          <div className="text-xs space-y-1">
            <p>
              <strong>Lat:</strong> {currentLocation.latitude.toFixed(6)}
            </p>
            <p>
              <strong>Lng:</strong> {currentLocation.longitude.toFixed(6)}
            </p>
            <p>
              <strong>Speed:</strong> {currentLocation.speed.toFixed(2)} km/h
            </p>
            <p>
              <strong>Satellites:</strong> {currentLocation.satellites}
            </p>
            <p>
              <strong>Time:</strong>{" "}
              {new Date(currentLocation.timestamp).toLocaleTimeString()}
            </p>
          </div>
        </div>
      )}

      {/* Debug info panel */}
      <div className="absolute bottom-4 left-4 z-[1000] bg-white p-3 rounded-md shadow-md max-w-xs">
        <h3 className="font-bold text-sm mb-2">Debug Info</h3>
        <div className="text-xs space-y-1">
          <p>
            <strong>Connection:</strong>{" "}
            {isConnected ? "Connected" : "Disconnected"}
          </p>
          <p>
            <strong>Location Data:</strong>{" "}
            {currentLocation ? "Received" : "None"}
          </p>
          <p>
            <strong>History Points:</strong> {locationHistory.length}
          </p>
          <p>
            <strong>Map Instance:</strong>{" "}
            {mapInstance ? "Initialized" : "Not initialized"}
          </p>
        </div>
        <div className="mt-2 space-y-1">
          <button
            onClick={async () => {
              try {
                console.log("Testing proxy connection...");
                const response = await fetch("/api/locations/stream", {
                  method: "GET",
                });
                console.log("Proxy response status:", response.status);
              } catch (error) {
                console.error("Proxy error:", error);
              }
            }}
            className="w-full px-2 py-1 bg-blue-500 text-white text-xs rounded hover:bg-blue-600"
          >
            Test Proxy
          </button>
          <button
            onClick={() => {
              console.log("Testing with sample data...");
              const sampleData: Location = {
                latitude: 29.6608,
                longitude: 74.41905333333334,
                altitude: 0.0,
                speed: 45.0,
                course: 29,
                valid: true,
                timestamp: new Date().toISOString(),
                satellites: 207,
              };
              setCurrentLocation(sampleData);
              setLocationHistory([[sampleData.latitude, sampleData.longitude]]);
            }}
            className="w-full px-2 py-1 bg-green-500 text-white text-xs rounded hover:bg-green-600"
          >
            Test Sample Data
          </button>
        </div>
      </div>

      {/* Map container */}
      <MapWrapper
        currentLocation={currentLocation}
        locationHistory={locationHistory}
        defaultCenter={defaultCenter}
        currentCenter={currentCenter}
      />
    </div>
  );
};

export default LiveMap;
