using System;
using System.IO;

namespace OriginSetup.Engine;

// LZMA decoder for one ".lzma" (LZMA-alone) stream, decoded straight into a
// caller-sized output buffer.
//
// Why it exists: the installer targets .NET Framework 4.7.2 (built into every
// Windows 10/11, so setup.exe needs no runtime and stays small), which has
// Deflate but no LZMA. LZMA packs the launcher ~45 MB smaller than a zip
// (81 MB vs 126 MB measured on v1.0.39), keeping setup.exe the size the old
// Inno Setup installer was.
//
// A direct port of the LZMA SDK's reference decoder (LzmaSpec.cpp, Igor
// Pavlov, public domain) — same names and structure so it can be checked
// against the spec line by line. One simplification: every payload chunk
// is decoded whole into its own buffer, so the "dictionary window" is just
// the output array itself (no ring buffer, no wrap-around).
//
// Verified by src/OriginSetup.Tests: random/repetitive/edge data round-trips
// against Python's liblzma encoder, plus the real launcher payload.
public static class Lzma
{
    private const int NumBitModelTotalBits = 11;
    private const uint BitModelTotal = 1u << NumBitModelTotalBits;
    private const int NumMoveBits = 5;
    private const ushort ProbInit = (ushort)(BitModelTotal / 2);
    private const uint TopValue = 1u << 24;

    private const int NumPosBitsMax = 4;
    private const int NumStates = 12;
    private const int NumLenToPosStates = 4;
    private const int NumAlignBits = 4;
    private const int StartPosModelIndex = 4;
    private const int EndPosModelIndex = 14;
    private const int NumFullDistances = 1 << (EndPosModelIndex >> 1);
    private const int MatchMinLen = 2;

    public const int HeaderSize = 13; // 5 property bytes + 8-byte unpacked size

    /// <summary>
    /// Decodes the .lzma stream input[offset..offset+count) into output, which
    /// must be exactly the unpacked size. Reports progress (bytes produced so
    /// far) roughly every MiB. Throws InvalidDataException on corrupt data.
    /// </summary>
    public static void Decode(byte[] input, int offset, int count, byte[] output, Action<int>? progress = null)
    {
        if (count < HeaderSize) throw new InvalidDataException("LZMA stream too short.");
        var d = new Decoder(input, offset, count, output, progress);
        d.Run();
    }

    private sealed class RangeDecoder
    {
        private readonly byte[] _in;
        private int _pos;
        private readonly int _end;
        public uint Range;
        public uint Code;
        public bool Corrupted;

        public RangeDecoder(byte[] input, int pos, int end)
        {
            _in = input;
            _pos = pos;
            _end = end;
        }

        private byte ReadByte()
        {
            if (_pos >= _end) throw new InvalidDataException("LZMA stream ended early.");
            return _in[_pos++];
        }

        public void Init()
        {
            Corrupted = false;
            Range = 0xFFFFFFFF;
            Code = 0;
            byte b = ReadByte();
            for (int i = 0; i < 4; i++)
                Code = (Code << 8) | ReadByte();
            if (b != 0 || Code == Range)
                Corrupted = true;
        }

        public bool IsFinishedOK => Code == 0;

        private void Normalize()
        {
            if (Range < TopValue)
            {
                Range <<= 8;
                Code = (Code << 8) | ReadByte();
            }
        }

        public uint DecodeDirectBits(int numBits)
        {
            uint res = 0;
            do
            {
                Range >>= 1;
                Code -= Range;
                uint t = 0u - (Code >> 31);
                Code += Range & t;
                if (Code == Range)
                    Corrupted = true;
                Normalize();
                res <<= 1;
                res += t + 1;
            }
            while (--numBits != 0);
            return res;
        }

        public uint DecodeBit(ushort[] probs, int index)
        {
            uint v = probs[index];
            uint bound = (Range >> NumBitModelTotalBits) * v;
            uint symbol;
            if (Code < bound)
            {
                v += (BitModelTotal - v) >> NumMoveBits;
                Range = bound;
                symbol = 0;
            }
            else
            {
                v -= v >> NumMoveBits;
                Code -= bound;
                Range -= bound;
                symbol = 1;
            }
            probs[index] = (ushort)v;
            Normalize();
            return symbol;
        }
    }

    private static ushort[] NewProbs(int n)
    {
        var p = new ushort[n];
        for (int i = 0; i < n; i++) p[i] = ProbInit;
        return p;
    }

    private static uint BitTreeReverseDecode(ushort[] probs, int baseIndex, int numBits, RangeDecoder rc)
    {
        uint m = 1;
        uint symbol = 0;
        for (int i = 0; i < numBits; i++)
        {
            uint bit = rc.DecodeBit(probs, baseIndex + (int)m);
            m <<= 1;
            m += bit;
            symbol |= bit << i;
        }
        return symbol;
    }

    private sealed class BitTree
    {
        private readonly int _numBits;
        private readonly ushort[] _probs;

        public BitTree(int numBits)
        {
            _numBits = numBits;
            _probs = NewProbs(1 << numBits);
        }

        public uint Decode(RangeDecoder rc)
        {
            uint m = 1;
            for (int i = 0; i < _numBits; i++)
                m = (m << 1) + rc.DecodeBit(_probs, (int)m);
            return m - (1u << _numBits);
        }

        public uint ReverseDecode(RangeDecoder rc) => BitTreeReverseDecode(_probs, 0, _numBits, rc);
    }

    private sealed class LenDecoder
    {
        private readonly ushort[] _choice = NewProbs(2); // [0] Choice, [1] Choice2
        private readonly BitTree[] _low = new BitTree[1 << NumPosBitsMax];
        private readonly BitTree[] _mid = new BitTree[1 << NumPosBitsMax];
        private readonly BitTree _high = new BitTree(8);

        public LenDecoder()
        {
            for (int i = 0; i < _low.Length; i++)
            {
                _low[i] = new BitTree(3);
                _mid[i] = new BitTree(3);
            }
        }

        public uint Decode(RangeDecoder rc, int posState)
        {
            if (rc.DecodeBit(_choice, 0) == 0)
                return _low[posState].Decode(rc);
            if (rc.DecodeBit(_choice, 1) == 0)
                return 8 + _mid[posState].Decode(rc);
            return 16 + _high.Decode(rc);
        }
    }

    private sealed class Decoder
    {
        private readonly RangeDecoder _rc;
        private readonly byte[] _out;
        private int _outPos;
        private readonly Action<int>? _progress;

        private readonly int _lc, _lp, _pb;
        private readonly uint _dictSize;
        private readonly ushort[] _litProbs;
        private readonly BitTree[] _posSlot = new BitTree[NumLenToPosStates];
        private readonly BitTree _align = new BitTree(NumAlignBits);
        private readonly ushort[] _posDecoders = NewProbs(1 + NumFullDistances - EndPosModelIndex);
        private readonly ushort[] _isMatch = NewProbs(NumStates << NumPosBitsMax);
        private readonly ushort[] _isRep = NewProbs(NumStates);
        private readonly ushort[] _isRepG0 = NewProbs(NumStates);
        private readonly ushort[] _isRepG1 = NewProbs(NumStates);
        private readonly ushort[] _isRepG2 = NewProbs(NumStates);
        private readonly ushort[] _isRep0Long = NewProbs(NumStates << NumPosBitsMax);
        private readonly LenDecoder _lenDecoder = new LenDecoder();
        private readonly LenDecoder _repLenDecoder = new LenDecoder();

        public Decoder(byte[] input, int offset, int count, byte[] output, Action<int>? progress)
        {
            int d = input[offset];
            if (d >= 9 * 5 * 5) throw new InvalidDataException("Bad LZMA properties.");
            _lc = d % 9;
            d /= 9;
            _lp = d % 5;
            _pb = d / 5;
            uint dictSizeInProperties = 0;
            for (int i = 0; i < 4; i++)
                dictSizeInProperties |= (uint)input[offset + 1 + i] << (8 * i);
            _dictSize = Math.Max(dictSizeInProperties, 1u << 12);
            // Bytes 5..12 are the stream's own unpacked size (often "unknown",
            // 0xFF..FF, from encoders that write an end marker). The payload
            // header's size is authoritative, so it is not read here.

            _litProbs = NewProbs(0x300 << (_lc + _lp));
            for (int i = 0; i < NumLenToPosStates; i++)
                _posSlot[i] = new BitTree(6);

            _rc = new RangeDecoder(input, offset + HeaderSize, offset + count);
            _out = output;
            _progress = progress;
        }

        private bool IsEmpty => _outPos == 0;
        private byte GetByte(uint dist) => _out[_outPos - (int)dist];

        private void PutByte(byte b) => _out[_outPos++] = b;

        private void CopyMatch(uint dist, int len)
        {
            int src = _outPos - (int)dist;
            for (; len > 0; len--)
                _out[_outPos++] = _out[src++];
        }

        private void DecodeLiteral(int state, uint rep0)
        {
            uint prevByte = IsEmpty ? 0u : GetByte(1);
            uint symbol = 1;
            int litState = (int)(((uint)_outPos & ((1u << _lp) - 1)) << _lc) + (int)(prevByte >> (8 - _lc));
            int probsBase = 0x300 * litState;

            if (state >= 7)
            {
                uint matchByte = GetByte(rep0 + 1);
                do
                {
                    uint matchBit = (matchByte >> 7) & 1;
                    matchByte <<= 1;
                    uint bit = _rc.DecodeBit(_litProbs, probsBase + (int)(((1 + matchBit) << 8) + symbol));
                    symbol = (symbol << 1) | bit;
                    if (matchBit != bit)
                        break;
                }
                while (symbol < 0x100);
            }
            while (symbol < 0x100)
                symbol = (symbol << 1) | _rc.DecodeBit(_litProbs, probsBase + (int)symbol);
            PutByte((byte)(symbol - 0x100));
        }

        private uint DecodeDistance(uint len)
        {
            uint lenState = len;
            if (lenState > NumLenToPosStates - 1)
                lenState = NumLenToPosStates - 1;

            uint posSlot = _posSlot[lenState].Decode(_rc);
            if (posSlot < 4)
                return posSlot;

            int numDirectBits = (int)((posSlot >> 1) - 1);
            uint dist = (2 | (posSlot & 1)) << numDirectBits;
            if (posSlot < EndPosModelIndex)
            {
                dist += BitTreeReverseDecode(_posDecoders, (int)(dist - posSlot), numDirectBits, _rc);
            }
            else
            {
                dist += _rc.DecodeDirectBits(numDirectBits - NumAlignBits) << NumAlignBits;
                dist += _align.ReverseDecode(_rc);
            }
            return dist;
        }

        private static int UpdateStateLiteral(int state) => state < 4 ? 0 : state < 10 ? state - 3 : state - 6;
        private static int UpdateStateMatch(int state) => state < 7 ? 7 : 10;
        private static int UpdateStateRep(int state) => state < 7 ? 8 : 11;
        private static int UpdateStateShortRep(int state) => state < 7 ? 9 : 11;

        public void Run()
        {
            _rc.Init();
            if (_rc.Corrupted) throw new InvalidDataException("Corrupt LZMA stream (bad range coder start).");

            uint rep0 = 0, rep1 = 0, rep2 = 0, rep3 = 0;
            int state = 0;
            int unpackSize = _out.Length;
            int nextReport = 1 << 20;

            for (;;)
            {
                if (_outPos >= nextReport)
                {
                    _progress?.Invoke(_outPos);
                    nextReport = _outPos + (1 << 20);
                }

                // The output is full: the stream either ends here with no marker
                // (range coder fully drained), or carries an end marker next.
                if (unpackSize == 0 && _rc.IsFinishedOK)
                    break;

                int posState = _outPos & ((1 << _pb) - 1);

                if (_rc.DecodeBit(_isMatch, (state << NumPosBitsMax) + posState) == 0)
                {
                    if (unpackSize == 0) throw new InvalidDataException("LZMA data past the expected size.");
                    DecodeLiteral(state, rep0);
                    state = UpdateStateLiteral(state);
                    unpackSize--;
                    continue;
                }

                uint len;
                if (_rc.DecodeBit(_isRep, state) != 0)
                {
                    if (unpackSize == 0) throw new InvalidDataException("LZMA data past the expected size.");
                    if (IsEmpty) throw new InvalidDataException("Corrupt LZMA stream (rep at start).");
                    if (_rc.DecodeBit(_isRepG0, state) == 0)
                    {
                        if (_rc.DecodeBit(_isRep0Long, (state << NumPosBitsMax) + posState) == 0)
                        {
                            state = UpdateStateShortRep(state);
                            PutByte(GetByte(rep0 + 1));
                            unpackSize--;
                            continue;
                        }
                    }
                    else
                    {
                        uint dist;
                        if (_rc.DecodeBit(_isRepG1, state) == 0)
                        {
                            dist = rep1;
                        }
                        else
                        {
                            if (_rc.DecodeBit(_isRepG2, state) == 0)
                            {
                                dist = rep2;
                            }
                            else
                            {
                                dist = rep3;
                                rep3 = rep2;
                            }
                            rep2 = rep1;
                        }
                        rep1 = rep0;
                        rep0 = dist;
                    }
                    len = _repLenDecoder.Decode(_rc, posState);
                    state = UpdateStateRep(state);
                }
                else
                {
                    rep3 = rep2;
                    rep2 = rep1;
                    rep1 = rep0;
                    len = _lenDecoder.Decode(_rc, posState);
                    state = UpdateStateMatch(state);
                    rep0 = DecodeDistance(len);
                    if (rep0 == 0xFFFFFFFF)
                    {
                        // End marker: valid only when the output is complete.
                        if (_rc.IsFinishedOK && unpackSize == 0) break;
                        throw new InvalidDataException("Corrupt LZMA stream (unexpected end marker).");
                    }
                    if (unpackSize == 0) throw new InvalidDataException("LZMA data past the expected size.");
                    if (rep0 >= _dictSize || rep0 >= (uint)_outPos)
                        throw new InvalidDataException("Corrupt LZMA stream (distance out of range).");
                }

                int matchLen = (int)len + MatchMinLen;
                if (matchLen > unpackSize)
                    throw new InvalidDataException("LZMA match runs past the expected size.");
                CopyMatch(rep0 + 1, matchLen);
                unpackSize -= matchLen;
            }

            if (_rc.Corrupted) throw new InvalidDataException("Corrupt LZMA stream.");
            _progress?.Invoke(_outPos);
        }
    }
}
