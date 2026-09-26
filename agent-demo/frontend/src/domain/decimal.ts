const MAX_LONG = 9223372036854775807n;

export function decimal(value: unknown): string {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(value) || BigInt(value) > MAX_LONG) {
    throw new Error('INVALID_RESPONSE');
  }
  return value;
}

export function compareDecimal(left: string, right: string): number {
  const a = BigInt(decimal(left));
  const b = BigInt(decimal(right));
  return a === b ? 0 : a > b ? 1 : -1;
}
