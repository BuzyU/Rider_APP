import { type NextRequest, NextResponse } from 'next/server'
import { createClient } from './utils/supabase/middleware'

export async function middleware(request: NextRequest) {
  const response = await createClient(request)
  const { pathname } = request.nextUrl

  if (pathname.startsWith('/api/admin') && pathname !== '/api/admin/session') {
    const hasSession = request.cookies.get('__session')?.value
    const isAdmin = request.cookies.get('rv_admin')?.value === '1'

    if (!hasSession || !isAdmin) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }
  }

  if (pathname.startsWith('/admin') && pathname !== '/admin/login') {
    const hasSession = request.cookies.get('__session')?.value
    const isAdmin = request.cookies.get('rv_admin')?.value === '1'

    if (!hasSession || !isAdmin) {
      const loginUrl = request.nextUrl.clone()
      loginUrl.pathname = '/admin/login'
      return NextResponse.redirect(loginUrl)
    }
  }

  return response
}

export const config = {
  matcher: [
    '/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp)$).*)',
  ],
}
