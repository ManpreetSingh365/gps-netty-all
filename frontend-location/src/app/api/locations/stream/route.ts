
import { NextRequest } from 'next/server'

export async function GET(request: NextRequest) {
  // Set CORS headers for SSE
  const headers = new Headers({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    'Connection': 'keep-alive',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET',
    'Access-Control-Allow-Headers': 'Cache-Control',
  })

  let upstreamResponse: Response | null = null
  let upstreamReader: ReadableStreamDefaultReader<Uint8Array> | null = null

  try {
    // Connect to the backend SSE stream
    upstreamResponse = await fetch("http://178.16.139.69:8082/locations/stream", {
      headers: {
        Accept: "text/event-stream",
        "Cache-Control": "no-cache",
      },
      signal: request.signal, // Forward abort signal
    })

    if (!upstreamResponse.ok) {
      console.error(`Backend responded with status: ${upstreamResponse.status}`)
      return new Response(
        `data: {"error": "Backend connection failed", "status": ${upstreamResponse.status}}\n\n`,
        { 
          status: 502,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
          }
        }
      )
    }

    if (!upstreamResponse.body) {
      throw new Error('No response body from backend')
    }

    upstreamReader = upstreamResponse.body.getReader()

    const stream = new ReadableStream({
      async start(controller) {
        const decoder = new TextDecoder()

        try {
          while (true) {
            const { done, value } = await upstreamReader!.read()

            if (done) {
              console.log('Backend stream ended')
              break
            }

            // Check if client disconnected
            if (controller.desiredSize === null) {
              console.log('Client disconnected, stopping stream')
              break
            }

            // Forward the chunk to the client
            controller.enqueue(value)
          }
        } catch (error) {
          console.error('Stream processing error:', error)

          // Send error message to client if still connected
          if (controller.desiredSize !== null) {
            const errorMessage = `data: {"error": "Connection failed", "message": "${(error as Error).message}"}\n\n`;
            controller.enqueue(new TextEncoder().encode(errorMessage))
            controller.error(error)
          }
        } finally {
          // Clean up resources
          try {
            upstreamReader?.releaseLock()
          } catch (e) {
            console.warn('Error releasing reader lock:', e)
          }

          if (controller.desiredSize !== null) {
            controller.close()
          }
        }
      },

      cancel(reason) {
        console.log('Stream cancelled by client:', reason)
        // Clean up upstream connection
        try {
          upstreamReader?.cancel(reason)
        } catch (e) {
          console.warn('Error cancelling upstream reader:', e)
        }
      }
    })

    return new Response(stream, { headers })

  } catch (error) {
    console.error("Error in SSE proxy:", error)

    // Clean up resources on error
    try {
      upstreamReader?.releaseLock()
    } catch (e) {
      console.warn('Error releasing reader lock in error handler:', e)
    }

    // Return error as SSE event
    const errorMessage = `data: {"error": "Connection failed", "message": "${(error as Error).message}"}\n\n`;
    return new Response(errorMessage, { 
      status: 500,
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
      }
    })
  }
}

// Handle preflight requests
export async function OPTIONS() {
  return new Response(null, {
    status: 200,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': 'Cache-Control',
    },
  })
}
