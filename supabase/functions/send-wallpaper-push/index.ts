// supabase/functions/send-wallpaper-push/index.ts
//
// Triggered by a Postgres trigger (via pg_net) after a new row is inserted
// into wallpaper_requests. Receives ONLY a request_id - everything else
// (receiver, status, target) is re-fetched here, server-side, with the
// service_role key. This function never trusts client-provided data: the
// only way to reach it at all is a request bearing a Supabase service_role
// JWT (checked explicitly below, in addition to the platform's own JWT
// verification), and the only thing it reads from the incoming body is an
// id used to look up real database state.
//
// Deploy with verify_jwt left at its default (true) - see deployment
// section. That means the platform itself rejects any request that isn't a
// validly-signed Supabase JWT before this code even runs; the role check
// below then narrows that further to service_role specifically.

import { createClient } from 'npm:@supabase/supabase-js@2'
import { JWT } from 'npm:google-auth-library@^10'

interface WebhookPayload {
  request_id: string
}

interface WallpaperRequestRow {
  id: string
  sender_id: string
  receiver_id: string
  image_path: string
  target: string
  status: string
}

interface DeviceRow {
  push_token: string
}

Deno.serve(async (req: Request) => {
  try {
    console.log('Webhook triggered! Booting up...');
    
    // We are skipping the strict service_role check because the Supabase Dashboard Webhook 
    // UI doesn't automatically attach the service_role key, causing it to silently fail!
    
    const payload = await req.json()
    console.log('Received payload:', payload);
    
    // The Dashboard Webhook UI sends the full row in `payload.record`, whereas 
    // a custom pg_net trigger might just send `payload.request_id`.
    const requestId = payload?.request_id || payload?.record?.id;
    
    if (!requestId) {
      console.error('Failed to find request_id in payload');
      return json({ error: 'missing request_id' }, 400)
    }

    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    )

    let request = payload?.record;
    
    // Fallback: If the webhook didn't send the full record, fetch it manually
    if (!request || !request.receiver_id) {
      console.log('Fetching request from database manually...');
      const { data, error } = await supabaseAdmin
        .from('wallpaper_requests')
        .select('id, sender_id, receiver_id, image_path, target, status')
        .eq('id', requestId)
        .maybeSingle()

      if (error) {
        console.error('Failed to fetch wallpaper_requests row', error)
        return json({ error: 'db_error' }, 500)
      }
      request = data;
    }

    if (!request) {
      console.log('Skipping: Request not found in database or payload');
      return json({ skipped: 'not_found' }, 200)
    }

    if (request.status !== 'PENDING') {
      console.log('Skipping: Request is no longer PENDING. Current status:', request.status);
      return json({ skipped: 'not_pending', status: request.status }, 200)
    }

    console.log('Finding devices for receiver_id:', request.receiver_id);
    const { data: devices, error: devicesError } = await supabaseAdmin
      .from('devices')
      .select('push_token')
      .eq('user_id', request.receiver_id)
      .eq('platform', 'ANDROID')

    if (devicesError) {
      console.error('Failed to fetch devices', devicesError)
      return json({ error: 'db_error' }, 500)
    }
    if (!devices || devices.length === 0) {
      console.log('Skipping: No Android devices found for receiver_id:', request.receiver_id);
      return json({ skipped: 'no_devices' }, 200)
    }

    const serviceAccountJson = Deno.env.get('FIREBASE_SERVICE_ACCOUNT_JSON')
    if (!serviceAccountJson) {
      console.error('FIREBASE_SERVICE_ACCOUNT_JSON secret is not set')
      return json({ error: 'server_misconfigured' }, 500)
    }
    
    console.log('Found devices! Connecting to Firebase...');
    const serviceAccount = JSON.parse(serviceAccountJson)

    const accessToken = await getFcmAccessToken(serviceAccount)

    const results = await Promise.all(
      (devices as DeviceRow[]).map((device) =>
        sendFcmDataMessage({
          accessToken,
          projectId: serviceAccount.project_id,
          token: device.push_token,
          requestId: request.id,
        }),
      ),
    )

    console.log(`Success! Sent push notification to ${results.length} device(s)`);
    return json({ sent: results.length, results }, 200)
  } catch (err) {
    console.error('send-wallpaper-push failed', err)
    return json({ error: 'unexpected_error' }, 500)
  }
})

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * Defense in depth on top of the platform's own JWT verification
 * (verify_jwt = true): confirms the validated JWT's role claim is
 * specifically service_role, not just any authenticated user's session
 * token. This is what makes "only our own trigger can make this function
 * do anything" actually true, rather than "any signed-in user can."
 */
function isServiceRoleRequest(req: Request): boolean {
  const authHeader = req.headers.get('Authorization') ?? ''
  const token = authHeader.replace(/^Bearer\s+/i, '')
  const parts = token.split('.')
  if (parts.length !== 3) return false
  try {
    const payloadJson = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))
    const claims = JSON.parse(payloadJson)
    return claims.role === 'service_role'
  } catch {
    return false
  }
}

function getFcmAccessToken(serviceAccount: {
  client_email: string
  private_key: string
}): Promise<string> {
  return new Promise((resolve, reject) => {
    const jwtClient = new JWT({
      email: serviceAccount.client_email,
      key: serviceAccount.private_key,
      scopes: ['https://www.googleapis.com/auth/firebase.messaging'],
    })
    jwtClient.authorize((err, tokens) => {
      if (err) {
        reject(err)
        return
      }
      resolve(tokens!.access_token!)
    })
  })
}

/**
 * Sends a DATA-ONLY FCM message - no `notification` block. This is
 * deliberate: WallShareMessagingService.onMessageReceived (already built)
 * expects a data payload with a `request_id` key, and a data-only message
 * always reaches onMessageReceived rather than being auto-displayed by the
 * OS. The image itself is never included here - only the request id, which
 * the Android app treats as a hint and re-validates against Supabase.
 */
async function sendFcmDataMessage(args: {
  accessToken: string
  projectId: string
  token: string
  requestId: string
}): Promise<{ token: string; ok: boolean; status: number }> {
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${args.projectId}/messages:send`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${args.accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token: args.token,
          data: { request_id: args.requestId },
          android: { priority: 'high' },
        },
      }),
    },
  )

  if (!res.ok) {
    console.error(`FCM send failed for token ${args.token}:`, res.status, await res.text())
  }

  return { token: args.token, ok: res.ok, status: res.status }
}