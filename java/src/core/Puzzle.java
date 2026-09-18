package core;

import java.util.Random;
import java.io.IOException;

/* 
 * Prototype program to solve Eternity II (16 x 16)
 * 
 * @author: Daniel Lundh
 * @date: 27/07/07
 */
public class Puzzle
{
	public static void main(String args[]) throws IOException
	{
		//Create board and piece sizes
		//Board size: 16 x 16, can hold 4 sides of a piece, 
		//and can remember every piece available that has tried to fit in a certain spot
		int[][][] board = new int[16][16][4];
				
		//256 pieces, 4 sides	
		int[][] pieces = new int[256][4];
		
		//Piece side values:
		//Left side: 0
		//Top side: 1
		//Right side: 2
		//Bottom side: 3
		
		//Parameters to check if a piece has been laid or not or should be skipped, contains every piece in puzzle 
		//[1] = skip piece or not
		//[256] = 256 pieces total		
		boolean[][] addedPieces = new boolean[2][256];
		
		//Remember all places where a piece has been tried without success
		//All pieces, max available spots at a time in a frame, board coordinates
		int[][][] pieceTriedWhere = new int[256][56][2];
		
		//Piece position in pieces array
		int i = 0;	
		
		//Used for random number generation
		int r = 0;
		
		//Store board (x,y) coordinates
		int[] coords = new int[2];
		
		//Hold all available board spot coordinates
		//Maximum 56 spots per frame
		int[][] availableSpots = new int[56][2];
		
		//Keep track of how many times we have reset i (piece position)
		int resetCounter = 0;
		
		//Keep track of spots left in current frame
		int spotsLeft = -1;
		
		//Keep track of what order pieces were placed so we can undo placements and find alternate patterns
		//256 pieces, 2 board coordinates for each piece
		int[][] piecePlacedWhere = new int[256][2];
		
		//Keep track of board spots that have opened up after pieces have been removed
		int[][] clearedSpots = new int[256][2];
		
		//Web UI disabled
		int[] pieceRotations = new int[256];
		
		//Piece 139 is fixed in spot (7,8) from start
		int[] piece139 = {8,6,16,16};
		board[7][8] = piece139;
		addedPieces[1][138] = true;	
		piecePlacedWhere[138][0] = 7;
		piecePlacedWhere[138][1] = 8;
		
		//Add board pieces to arrays
		Pieces.SetupPieces(pieces);		
		//Place inner frames one by one
		PlaceFramePieces(coords, board, pieces, addedPieces, pieceTriedWhere, i, r, resetCounter, availableSpots, spotsLeft, piecePlacedWhere, pieceRotations);		
	}	
	
	public static void ResetAllSkippedPieces(boolean[][] addedPieces)
	{
		//Resets skipped array of addedPieces matrix
		for (int j = 0; j < addedPieces.length; j++)
		{ 
			addedPieces[0][j] = false;
		}
	}
	
	public static int FindFirstSkippedPiece(boolean[][] addedPieces)
	{
		for (int j = 0; j < addedPieces[0].length; j++)
		{
			if (addedPieces[0][j] == true)
			{
				//Found	a piece that has been skipped, return its position in pieces array			
				return j;
			}
		}
		//Did not find any skipped pieces
		return -1;
	}
	
	/*
	 * Will locate direction of border at (x,y)
	 * 
	 * @param x board coordinate to locate border from
	 * @param y board coordinate to locate border from
	 * @return j that store the side that faces border
	 */
	public static int FindBorderSide(int x, int y)
	{	
		//Loop 4 times to look in all directions for the border
		for (int j = 0; j < 4; j++)
		{
			//Temporary variables to check if surrounding board spots exist or not (=border)
			int v = 0;
			int w = 0;					
			
			//Prepare to check if board spot to the left exist
			if (j == 0)
			{						
				v = x - 1;
				w = y;	
			}
			
			//Prepare to check if board spot above exist
			if (j == 1)
			{						
				v = x;
				w = y - 1;							
			}
			
			//Prepare to check if board spot to the right exist
			if (j == 2)
			{						
				v = x + 1;
				w = y;						
			}
			
			//Prepare to check if board spot below exist
			if (j == 3)
			{						
				v = x;
				w = y + 1;		
			}
													
			if (v == -1 || w == -1 || v == 16 || w == 16)
			{	
				//Found a border side				
				return j;
			}
		}
		//No border side found
		return -1;
	}
	
	public static void AlignSidePieceWithBorder(int[][] pieces, int borderside, int[] pieceRotations, int i) throws IOException
	{
		for (int j = 0; j < 4; j++)
		{							
			//Check if piece has a grey side facing the border
			if (pieces[i][borderside] == 0)
			{								
				return;
			}
			else
			{				
				RotatePiece(pieces, pieceRotations, i);				
			}
		}		
		//System.out.println("Piece sent to be aligned with border was not a side piece");
	}

	public static int FindNextAvailablePieceNumber(boolean[][] addedPieces, int i, int end)
	{
		//Scan addedPieces array to find an available piece (maximum 56 pieces in frame starting at position i)
		for (int l = i; l < end; l++)
		{
			//Is piece added to the board? Is it skipped?
			if (addedPieces[1][l] == false && addedPieces[0][l] == false)
			{
				//Found an available piece
				return l;
			}
			
			//If piece is last available piece and is skipped, then we can't continue
			if (addedPieces[1][end-1] && addedPieces[0][end-1] == true)
			{
				return -1;
			}
		}
		//No available pieces found
		return -1;
	}
	
	public static boolean CheckIfPieceMatchesSpot(int x, int y, int[][][] board, int[][] pieces, int i, int borderside)
	{				
		
		//Check if sides of piece matches surrounding pieces, 0 means no piece in that direction						
		if (borderside == 0 || (board[x-1][y][2] == pieces[i][0] || board[x-1][y][2] == 0))
		{
			if (borderside == 1 || (board[x][y-1][3] == pieces[i][1] || board[x][y-1][3] == 0))
			{
				if (borderside == 2 || (board[x+1][y][0] == pieces[i][2] || board[x+1][y][0] == 0))
				{
					if (borderside == 3 || (board[x][y+1][1] == pieces[i][3] || board[x][y+1][1] == 0))
					{
						return true;
					}
				}
			}															
		}
		return false;
	}
	
	public static int PieceAlternativesForSpot(int[][][] board, int[][] pieces, int[] coords, int[] pieceRotations, int borderside, boolean[][] addedPieces, int i)
	{
		//Find other pieces that also fit the same spot, 
		//and randomize which one to use
		
		//Still need to find correct rotation of piece before using it
		
		Random generator = new Random();		
		int[] alternativePieces = new int[256];		
		int[] surroundingSidesOfSpot = new int[4];
		int counter = 0;
		int r = 0;
		int x = coords[0];
		int y = coords[1];
				
		for (int j = 0; j < 4; j++)
		{
			//Setting defaults to -1 so we don't mistake them for a border side (= 0)
			surroundingSidesOfSpot[j] = -1;
		}
		
		//Getting all surrounding sides of spot
		if (x > 0)
		{
			surroundingSidesOfSpot[0] = board[x-1][y][2];
		}
		if (y > 0)
		{
			surroundingSidesOfSpot[1] = board[x][y-1][3];
		}
		if (x < 15)
		{
			surroundingSidesOfSpot[2] = board[x+1][y][0];
		}
		if (y < 15)
		{
			surroundingSidesOfSpot[3] = board[x][y+1][1];
		}		
		
		//Side frame corner pieces are exempt
		if (i > 3)
		{
			//Finding other pieces with same side patterns
			for (int j = i; j < pieces.length; j++)
			{
				if (addedPieces[1][j] != true)
				{
					for (int k = 0; k < 4; k++)
					{
						if (surroundingSidesOfSpot[0] == 0 || (surroundingSidesOfSpot[0] == -1  && pieces[j][borderside] == 0) || pieces[j][0] == surroundingSidesOfSpot[0])
						{	
							if (surroundingSidesOfSpot[1] == 0 || (surroundingSidesOfSpot[1] == -1  && pieces[j][borderside] == 0) || pieces[j][1] == surroundingSidesOfSpot[1])
							{
								if (surroundingSidesOfSpot[2] == 0 || (surroundingSidesOfSpot[2] == -1  && pieces[j][borderside] == 0) || pieces[j][2] == surroundingSidesOfSpot[2])
								{					
									if (surroundingSidesOfSpot[3] == 0 || (surroundingSidesOfSpot[3] == -1  && pieces[j][borderside] == 0) || pieces[j][3] == surroundingSidesOfSpot[3])
									{
										alternativePieces[counter] = j;
										counter++;
										break;
									}
								}
							}
						}
						
						RotatePiece(pieces, pieceRotations, j);
					}
				}
			}
		}
		
		//Select a random piece that fits in spot
		if (counter > 0)
		{
			r = generator.nextInt(counter);
			return alternativePieces[r];
		}
		else
		{
			//Return first matching piece found if no alternate pieces exist
			return i;
		}
	}
	
	public static void SkipPiece(boolean[][] addedPieces, int i)
	{
		if (addedPieces[0][i] != true)
		{			
			addedPieces[0][i] = true;			
		}			
	}
	
	public static void StorePieceTriedWhere(int[][] availableSpots, int[][][] pieceTriedWhere, boolean[][] addedPieces, int i, int r)
	{
		//Finding an empty spot to fill in new board coordinates for a spot that did not fit the piece
		for (int j = 0; j < pieceTriedWhere[i].length; j++)
		{
			if (pieceTriedWhere[i][j][0] == 0 && pieceTriedWhere[i][j][1] == 0)
			{
				pieceTriedWhere[i][j][0] = availableSpots[r][0];
				pieceTriedWhere[i][j][1] = availableSpots[r][1];
				//System.out.println("(Stored that piece " + (i+1) + " has been at position (" + spots[r][0] + "," + spots[r][1] + "). Try " + (j+1) + " for this piece to find a spot!");
				return;
			}			
		}	
	}
	
	public static boolean IsSpotTestedAlready(int[][][] pieceTriedWhere, int[][] availableSpots, int i, int r, int start, int end)
	{		
		//Finding out if piece has been tested at current spot already	
		for (int j = 0; j < (end-start); j++)
		{
			if (availableSpots[r][0] == pieceTriedWhere[i][j][0] && availableSpots[r][1] == pieceTriedWhere[i][j][1])
			{	
				return true;
			}
		}
		//System.out.println("Piece " + (i+1) + " has not been matched at (" + spots[r][0] + "," + spots[r][1] + ") yet!");
		return false;
	}
	
	public static boolean PieceTriedEnough(int[][] availableSpots)
	{
		for (int j = 0; j < availableSpots.length; j++)
		{
			if (availableSpots[j][0] != 0 || availableSpots[j][1] != 0)
			{
				//Current piece needs to be tested at more board spots that are available
				return false;
			}
		}
		//Could not find any available spots in availableSpots array, 
		//which means that this piece has no place to be put
		return true;
	}
	
	public static int[][] StoreAvailableSpots(int[][] spots, int[][] availableSpots, int start, int end)
	{
		//Copy all coordinates between start and end from spots array to availableSpots array
		//These will be used for matching pieces with as few retries as possible
		for (int j = 0; j < (end-start); j++)
		{
			availableSpots[j][0] = spots[j+start][0];
			availableSpots[j][1] = spots[j+start][1];
		}
		
		return availableSpots;
	}
	
	public static int CountAvailableSpotsInFrame(int[][] availableSpots)
	{
		int spotsLeft = 0;
		
		//Counting number of available spots, excluding (0,0) coordinates which are deleted spots
		for (int j = 0; j < availableSpots.length; j++)
		{
			if (availableSpots[j][0] != 0 || availableSpots[j][1] != 0)
			{
				spotsLeft++;
			}
		}
		//System.out.println("*** There are " + spotsLeft + " spots left for this frame! ***");
		return spotsLeft;
	}	
	
	/*
	 * Finds an available piece that can switch place with a specified placed piece
	 * from the same frame.
	 */
	public static int FindBoardPieceToSwitchWith(int[][] availableSpots, int[][][] board, boolean[][] addedPieces, int[][][] pieceTriedWhere, int[][] piecePlacedWhere, int[] coords, int[][] pieces, int i, int start, int end)
	{
		//Get direction of side of current piece that is facing empty board spot
		int emptyspotside = -1;
		
		//Can current piece be used to switch specified piece with?
		boolean match = false;
		
		//Store 4 sides from current piece to match other pieces with
		int[] sidesStored = new int[4];
		
		//Compare all sides of placed frame pieces with current piece sides		
		for (int j = start; j < (end-start); j++)
		{
			int x = 0;
			int y = 0;
			
			//Store all current side patterns except the side facing empty spot
			for (int l = 0; l < 4; l++)
			{
				//Get coordinates for placed piece we want to switch
				if (piecePlacedWhere[j][0] != 0 || piecePlacedWhere[j][1] != 0)
				{
					x = piecePlacedWhere[j][0];
					y = piecePlacedWhere[j][1];
				}
				else
				{
					//This piece is not placed, try next piece
					break;
				}
				
				//Find direction from specified placed piece where side = 0 (empty spot)		
				if (x != 0 && board[x-1][y][2] == 0)
				{
					//Checking to the left for empty board spot
					emptyspotside = 0;
				}
				if (y != 0 && board[x][y-1][3] == 0)
				{
					//Checking above for empty board spot
					emptyspotside = 1;
				}
				if (x != 3 && board[x+1][y][0] == 0)
				{
					//Checking to the right for empty board spot
					emptyspotside = 2;
				}
				if (y != 3 && board[x][y+1][1] == 0)
				{
					//Checking below for empty board spot
					emptyspotside = 3;
				}		
				
				if (emptyspotside != j)
				{
					sidesStored[l] = board[x][y][l];
				}			
			}
			
			//Checking values of piece sides to know which direction we are matching against
			int newSide = 0;			
			
			//Only enter here if we found a placed piece
			if (x != 0 && y != 0)
			{
				//Need to rotate new piece to find out if it has a matching side initially,
				//before moving on to other sides
				for (int k = 0; k < 4; k++)
				{				
					if (k != emptyspotside)
					{
						//Found a side that matches with current piece side					
						if (sidesStored[k] == pieces[j][0])
						{						
							//Need to find out what value k is at opposite side of current piece
							if (k == 0 || k == 1)
							{
								newSide = k + 2;
							}
							
							if (k == 2 || k == 3)
							{
								newSide = k - 2;
							}
							
							//Checking if opposite sides of pieces match
							if (sidesStored[newSide] == pieces[j][2])
							{
								//Making sure that board piece does have a side facing an empty spot
								if (emptyspotside != -1)
								{
									//Need to match side opposite to empty spot side as well
									//Setting k to be opposite of empty spot side
									if (emptyspotside == 0 || emptyspotside == 1)
									{
										newSide = k + 2;
									}
									if (emptyspotside == 2 || emptyspotside == 3)
									{
										newSide = k - 2;
									}
									
									if (sidesStored[newSide] == pieces[j][1] || sidesStored[newSide] == pieces[j][3])
									{
										//We have found 3 sides that match the current available piece
										//4th side is not important since we have an empty spot side
										match = true;
									}
								}
							}					
							else
							{
								//This piece did not match enough sides, moving on to next piece...
								break;
							}
						}
					}
					if (match)
					{
						System.out.println("Piece " + (i+1) + " can substitute board piece " + (j+1) + " at spot (" + x + "," + y + ")");
						return j;
					}
				}
			}
		}		
		
		return -1;
	}
	
	/*
	 * Removes all stored data about placement of the specified piece so that it becomes completely
	 * removed from the board, which enables following pieces to take the spot instead.
	 */
	public static void UndoPlacement(int[][] availableSpots, int[][][] board, boolean[][] addedPieces, int[][][] pieceTriedWhere, int[][] piecePlacedWhere, int[] coords, int piecePosition)
	{
		
		//Remove coordinates from board (= remove piece from board)
		for (int j = 0; j < 4; j++)
		{
			board[coords[0]][coords[1]][j] = 0;
		}
		
		//Clear addedPieces array from piece
		addedPieces[1][piecePosition] = false;		
		addedPieces[0][piecePosition] = false;
		
		//Clear piece from pieceTriedWhere info
		for (int j = 0; j < pieceTriedWhere[piecePosition].length; j++)
		{
			if (pieceTriedWhere[piecePosition][j][0] != 0 || pieceTriedWhere[piecePosition][j][1] != 0)
			{
				pieceTriedWhere[piecePosition][j][0] = 0;
				pieceTriedWhere[piecePosition][j][1] = 0;
			}			
		}
		
		//Store coords in previous piece so that it won't try same spot again, we know that didn't work
		pieceTriedWhere[piecePosition][0][0] = coords[0];
		pieceTriedWhere[piecePosition][0][1] = coords[1];
		
		//Add coords back to availableSpots array
		for (int j = 0; j < availableSpots.length; j++)
		{
			if (availableSpots[j][0] == 0 && availableSpots[j][1] == 0)
			{
				availableSpots[j][0] = coords[0];
				availableSpots[j][1] = coords[1];
				break;
			}
		}
	}
	
	
	public static int GetPieceNumberFromCoords(int[][] piecePlacedWhere, int x, int y)
	{
		for (int j = 0; j < piecePlacedWhere.length; j++)
		{
			if (piecePlacedWhere[j][0] == x && piecePlacedWhere[j][1] == y)			
			{
				return j;
			}
		}
		
		//Did not find a piece at those coordinates
		return -1;
	}	
	
	public static int GetPieceNumberFromBoardAndPiecesArray(int[][][][] boardAndPieces, int x, int y)
	{
		for (int j = 0; j < boardAndPieces[1].length; j++)
		{
			//if (piecePlacedWhere[j][0] == x && piecePlacedWhere[j][1] == y)
			if (boardAndPieces[1][0][j][0] == x && boardAndPieces[1][0][j][1] == y)	
			{
				return j;
			}
		}
		
		//Did not find a piece at those coordinates
		return -1;
	}	
	
	public static int AdjacentPieceExist(int[][][] board, int[][] availableSpots, int r, int frameNumber)
	{
		//Need to know boundaries of frame so we know what direction to look for adjacent pieces
		//Subtract & add to frame boundaries for each frame we move in		
		int xLimitMin = 0 + frameNumber;
		int xLimitMax = 15 - frameNumber;
		int yLimitMin = 0 + frameNumber;
		int yLimitMax = 15 - frameNumber;		
		
		//Get coordinates for board spot so we can check surroundings for placed pieces
		int x = availableSpots[r][0];
		int y = availableSpots[r][1];	
		
		//Setting default values to coordinates for spot at position r,
		//so that we can test all directions right away
		int left = availableSpots[r][0];
		int right = availableSpots[r][0];
		int top = availableSpots[r][1];
		int below = availableSpots[r][1];					
		
		//Coordinates for all directions around randomly selected spot
		//Making sure surrounding spots are within board space
		if (x > xLimitMin)
		{
			left = x - 1;
		}
		
		if (x < xLimitMax)
		{
			right = x + 1;
		}
		
		if (y > yLimitMin)
		{
			top = y - 1;
		}
		
		if (y < yLimitMax)
		{
			below = y + 1;
		}		
		
		//If we find a single coordinate != 0, then we have an adjacent piece
		//Using return values 0-3 for directions
		if (board[left][y][0] != 0 || board[left][y][2] != 0)
		{			
			return 0;
		}		 
		if (board[right][y][0] != 0 || board[right][y][2] != 0)
		{
			return 2;
		}
		if (board[x][top][0] != 0 || board[x][top][2] != 0)
		{
			return 1;
		}
		if (board[x][below][0] != 0 || board[x][below][2] != 0)
		{
			return 3;
		}
		
		//No adjacent frame piece found
		return -1;
	}
	
	public static int[] FindNextAvailableSpotRandomly(boolean[][] addedPieces, int[][][] pieceTriedWhere, int[][] piecePlacedWhere, int[][][] board, int[][] spots, int[][] availableSpots, int[] coords, int spotsLeft, int[][] pieces, int r, int i, int start, int end, int frameNumber)
	{
		//Create random number generator
		Random generator = new Random();		
		//If we already have tested piece at a specified spot before
		boolean alreadytested = false;		
		//Keeping track of how many spots we have tried to match piece with
		int countSpotsTried = 0;
		//Need to keep track of when we have tried all spots efficiently
		boolean pieceTriedEnough = false;					
		//Count number of available spots where we can place a piece right away (spot has a nearby piece)
		int emptySpotsWithAdjacent = 0;
		
		while (!pieceTriedEnough)
		{						
			//Need to reset so that we get correct count each time through this loop
			countSpotsTried = 0;			
			
			//We need to find a random available board spot
			r = generator.nextInt(end-start);	
			
			//Coordinates for a spot to check for adjacent pieces
			int x = 0;
			int y = 0;
			
			//Only count adjacent spots once
			if (emptySpotsWithAdjacent == 0)
			{
				//Count number of available spots with adjacent pieces		
				for (int j = 0; j < availableSpots.length; j++)
				{
					x = availableSpots[j][0];
					y = availableSpots[j][1];
					
					if (x != 0 || y != 0)
					{
						if (AdjacentPieceExist(board, availableSpots, j, frameNumber) != -1)
						{
							emptySpotsWithAdjacent++;
						}
					}
				}
			}
			
			//For debugging purposes only
			if (emptySpotsWithAdjacent > 8)
			{
				//Something went wrong here
				System.out.println("*** ERROR: emptySpotsWithAdjacent is too large! ***");
			}
			
			for (int j = 0; j < pieceTriedWhere[i].length; j++)
			{ 
				if (pieceTriedWhere[i][j][0] != 0 || pieceTriedWhere[i][j][1] != 0)
				{
					countSpotsTried++;
				}
			}
						
			//Making sure that this random position holds a valid spot in availableSpots array
			//Also check if we have tested all possible spots with this piece yet
			if ((availableSpots[r][0] != 0 || availableSpots[r][1] != 0 ) && countSpotsTried <= emptySpotsWithAdjacent)
			{					
				//Need to make sure that there is a placed piece next to this spot
				if (AdjacentPieceExist(board, availableSpots, r, frameNumber) != -1)
				{								
					alreadytested = IsSpotTestedAlready(pieceTriedWhere, availableSpots, i, r, start, end);				
					
					if (!alreadytested)
					{						
						//Remember that we tried this spot with this piece
						StorePieceTriedWhere(availableSpots, pieceTriedWhere, addedPieces, i, r);
						
						//Is spot empty?
						if (board[availableSpots[r][0]][availableSpots[r][1]][0] == 0 && board[availableSpots[r][0]][availableSpots[r][1]][2] == 0)
						{
							coords[0] = availableSpots[r][0];
							coords[1] = availableSpots[r][1];							
							return coords;
						}					
					}					
				}
			}
			
			//If we have tested all available spots left that have adjacent pieces, 
			//then we need to skip this piece
			if (emptySpotsWithAdjacent <= countSpotsTried)
			{				
				//No available spots exist for this piece					
				//System.out.println("Piece " + (i+1) + " did not fit in any available spots...");
				
				//We could not switch this piece with a board piece anywhere in frame 
				pieceTriedEnough = true;
				
				//Skip piece since it cannot fit anywhere at this time
				SkipPiece(addedPieces, i);
			}
		}
		return coords;
	}
		
	public static void SwapPieces(int[][] availableSpots, int[][][] board, boolean[][] addedPieces, int[][][] pieceTriedWhere, int[][] piecePlacedWhere, int[] coords, int piecePosition, int[][] pieces, int[] pieceRotations, int i, int start, int end)
	{
		
		//Piece position of a matching piece to switch with
		int matchingPiece = 0;
						
		//Find another placed piece in frame to switch with
		matchingPiece = FindBoardPieceToSwitchWith(availableSpots, board, addedPieces, pieceTriedWhere, piecePlacedWhere, coords, pieces, i, start, end);
		
		if (matchingPiece != -1)
		{
			//Get coordinates for matching board piece
			coords[0] = piecePlacedWhere[matchingPiece][0];
			coords[1] = piecePlacedWhere[matchingPiece][1];		
			
			//Need to undo its placement
			UndoPlacement(availableSpots, board, addedPieces, pieceTriedWhere, piecePlacedWhere, coords, matchingPiece);
							
			
			
			//Now we need to place the matching piece at this spot at the right angle
			
			
			
			//Need to clear pieceTriedWhere here so that piece can be retried later
			for (int k = 0; k < pieceTriedWhere[0].length; k++)
			{
				pieceTriedWhere[matchingPiece][k][0] = 0;
				pieceTriedWhere[matchingPiece][k][1] = 0;
			}
		}
		if (matchingPiece == -1)
		{
			System.out.println("No board piece to swap piece " + (matchingPiece+1) + " with...");			
		}
	}
	
	
	public static int PlaceAlternatePieceAtNearbySpot(int[][] availableSpots, int[][][] board, boolean[][] addedPieces, int[][][] pieceTriedWhere, int[][] piecePlacedWhere, int[] coords, int piecePosition, int[][] pieces, int[] pieceRotations, int i, int r, int frameNumber, int start, int end)
	{
		
		//Direction of an adjacent piece to switch with
		int adjacentPieceDirection = -1;
		//Piece to switch placed one with
		int matchingPiece = -1;		
		//Current piece in adjacent spot
		int adjacentPlacedPiece = -1;
		//Current spot coordinates
		int x = coords[0];
		int y = coords[1];
		
		//Get direction of an adjacent spot with a placed piece to swap out
		adjacentPieceDirection = AdjacentPieceExist(board, availableSpots, r, frameNumber);
		
		if (adjacentPieceDirection == -1)
		{
			System.out.println("Error: No adjacent frame pieces can be found around board spot!");
			//Returning -1 to indicate that no direction from coords to alternate spot is returned
			return -1;
		}
		
		//Change coords to adjacent spot
		if (adjacentPieceDirection == 0)
		{
			x = x - 1;
		}
		if (adjacentPieceDirection == 1)
		{
			y = y - 1;
		}
		if (adjacentPieceDirection == 2)
		{
			x = x + 1;
		}
		if (adjacentPieceDirection == 3)
		{
			y = y + 1;
		}
		coords[0] = x;
		coords[1] = y;
		
		//Get piece number placed in adjacent spot
		adjacentPlacedPiece = GetPieceNumberFromCoords(piecePlacedWhere, x, y);
		
		//Find another available piece to switch with
		//We *could* be getting the same piece as we already have placed, need to check
		//Testing 10 times, if we still have the same piece number, we assume there are no alternative pieces
		for (int j = 0; j < 10; j++)
		{			
			matchingPiece = PieceAlternativesForSpot(board, pieces, coords, pieceRotations, 666, addedPieces, i);
			if (matchingPiece != adjacentPlacedPiece)
			{
				//Found a different random piece that matches this adjacent spot
				break;
			}			
		}
				
		if (matchingPiece == adjacentPlacedPiece)
		{
			System.out.println("No other available pieces matched (" + x + "," + y + ")! No swapping occurred.");
			return -1;
		}
							
		//Need to undo its placement
		UndoPlacement(availableSpots, board, addedPieces, pieceTriedWhere, piecePlacedWhere, coords, adjacentPlacedPiece);
							
		//Now we need to place the matching piece at this spot at the right angle
		//It should already have been rotated in place when checking for a match
		PlacePiece(x, y, board, pieces, addedPieces, availableSpots, matchingPiece, piecePlacedWhere, pieceTriedWhere, start, end);
		System.out.println("Piece " + matchingPiece + " has replaced piece " + adjacentPlacedPiece + " at (" + x + "," + y + ")!");
		return adjacentPieceDirection;
	}
	
	public static void PlacePiece(int x, int y, int[][][] board, int[][] pieces, boolean[][] addedPieces, int[][] availableSpots, int i, int[][] piecePlacedWhere, int[][][] pieceTriedWhere, int start, int end)
	{
		//Assign piece to board, need to make a clone or only memory locations of array are passed
		//which means that the arrays would be synchronized and values in pieces array would be deleted 
		//when deleting a value in board array
		board[x][y] = (int[]) pieces[i].clone();
		//Record that the piece has been placed			
		addedPieces[1][i] = true;	
		//Store what piece was placement order
		piecePlacedWhere[i][0] = x;
		piecePlacedWhere[i][1] = y;
		
		//Clearing all skipped pieces so that they become available again
		//since we have a new board pattern
		//Skipping first corner pieces
		if (i > 3)
		{
			for (int j = start; j < end; j++)
			{
				addedPieces[0][i] = false;				
			}
			
			//Clear all spots tried for all pieces in frame
			//Maximum 8 board spots available at any time
			for (int j = start; j < end; j++)
			{
				for (int k = 0; k < 56; k++)
				{
					pieceTriedWhere[j][k][0] = 0;
					pieceTriedWhere[j][k][1] = 0;
				}
			}
		}
		
		//Need to remove the coordinates (x,y) from availableSpots here
		for (int j = 0; j < availableSpots.length; j++)
		{
			if (availableSpots[j][0] == x && availableSpots[j][1] == y)
			{
				//(0,0) in availableSpots array means spot removed
				availableSpots[j][0] = 0;
				availableSpots[j][1] = 0;
			}
		}
		
		//System.out.println("Piece " + (i+1) + " has been put in place at (" + x + "," + y + ")!");		
		
	}	
	
	public static int[][][][] PlaceFramePieces(int[] coords, int[][][] board, int[][] pieces, boolean[][] addedPieces, int[][][] pieceTriedWhere, int i, int r, int resetCounter, int[][] availableSpots, int spotsLeft, int[][] piecePlacedWhere, int[] pieceRotations) throws IOException
	{		
		//spots array holds all available coordinates for the frame
		int[][] spots = new int[256][2];
		//Keep track of how many spots are left each run to see if outer frame
		//has been placed well. If average spots left is too high, redo outer frame. 
		int [] spotsLeftFrameStats = new int[50];
		//counter for spotsLeftFrameStats array
		int count = 0;
		
		//Test with returning both board setup and piecePlacedWhere for GUI
		int[][][][] boardAndPieces = new int[2][256][16][4];
		
		//Backup storage for reversing back to frame start when stuck
		int[][][] boardBackup = new int[16][16][4];
		int[][] availableSpotsBackup = new int[56][2];
		int[][] piecePlacedWhereBackup = new int[256][2];
		boolean[][] addedPiecesBackup = new boolean[2][256];		
		
		//Start and end number of spots in frame
		int startSpot = -1;
		int endSpot = -1;
		
		boolean allCorners = false;
		
		//Each loop is one frame, 8 frames total
		for (int j = 0; j < 8; j++) 
		{		
			//Side frame
			if (j == 0)
			{
				//Side coordinates
				spots = Coordinates.SideFrame();
				//Side frame starts here
				startSpot = 4;
				endSpot = 60;
			}			
			
			//Second frame
			if (j == 1)
			{				
				i = 60;	
				startSpot = 60;
				endSpot = 112;			
				spots = Coordinates.SecondFrame(spots);						
			}

			//Third frame
			if (j == 2)
			{					
				i = 112;		
				startSpot = 112;
				endSpot = 156;			
				spots = Coordinates.ThirdFrame(spots);				
			}
			
			//Fourth frame
			if (j == 3)
			{				
				startSpot = 156;
				endSpot = 192;
				spots = Coordinates.FourthFrame(spots);			
			}
			
			//Fifth frame
			if (j == 4)
			{
				startSpot = 192;
				endSpot = 220;
				spots = Coordinates.FifthFrame(spots);
			}
			
			//Sixth frame
			if (j == 5)
			{
				startSpot = 220;
				endSpot = 241;
				spots = Coordinates.SixthFrame(spots);
			}
			
			//Seventh inner frame
			if (j == 6)
			{
				startSpot = 240;
				endSpot = 252;
				spots = Coordinates.SeventhFrame(spots);
			}
			
			//Eight frame
			if (j == 7)
			{
				startSpot = 252;
				endSpot = 256;
				spots = Coordinates.EighthFrame(spots);
			}
			
			//Get all available spots
			availableSpots = StoreAvailableSpots(spots, availableSpots, startSpot, endSpot);
			//Place corner pieces first and store what piece numbers have been laid
			allCorners = PlaceCornerPieces(board, pieces, addedPieces, piecePlacedWhere, availableSpots, j, pieceRotations, i, pieceTriedWhere, startSpot, endSpot);
			if (!allCorners)
			{
				//Cannot complete puzzle at this point, need better techniques
				//j = -1;
				//return board;
				boardAndPieces[0] = board;
				boardAndPieces[1][0] = piecePlacedWhere;				
				return boardAndPieces;
				
			}			
			
			//All pieces placed in frame?
			boolean allpieces = false;			
			
			if (allCorners)
			{
				
				//Backup board placements so we can perform a reset later
				availableSpotsBackup = BackupFrameAvailableSpots(availableSpots);
				addedPiecesBackup = BackupFrameAddedPieces(addedPieces);
				boardBackup = BackupFrameBoard(board);
				piecePlacedWhereBackup = BackupFramePiecePlacedWhere(piecePlacedWhere);				
				System.arraycopy(piecePlacedWhere, 0, piecePlacedWhereBackup, 0, piecePlacedWhereBackup.length);
				
				
				while (!allpieces) 
				{
					boolean pieceFits = false;			
					int borderside = 0;
					
					if (j > 0)
					{
						//No border sides for pieces in frames inside side frame
						borderside = 666;
					}
	
					if (j == 0)
					{
						//starting from first available side piece
						i = FindNextAvailablePieceNumber(addedPieces, startSpot, endSpot);
					}
					else
					{
						//If inner frames, we work with all remaining pieces
						i = FindNextAvailablePieceNumber(addedPieces, startSpot, 256);
					}
	
					//If we reached the end of side pieces, we need to start checking for skipped pieces
					if (i == -1) 
					{
						//Count number of times we have reached end of array without success
						resetCounter++;
	
						//Find first skipped piece that we want to try again
						//Starting from after corner pieces in array...
						i = FindFirstSkippedPiece(addedPieces);
	
						if (i == -1) 
						{
							System.out.println("");
							System.out.println("All pieces in frame " + (j+1) + " have been placed!");
							System.out.println("");
							break;
						}
	
						//We have reached the limit of how many times we want to reset skipped pieces
						//Starting over with side pieces from scratch				
						if (resetCounter == 15) 
						{
							//Count number of available spots left in frame		
							spotsLeft = CountAvailableSpotsInFrame(availableSpots);
	
							System.out.println("");
							System.out.println("Reached maximum number of tries with this setup");
							System.out.println("There are " + spotsLeft	+ " pieces in frame " + (j+1) + " that could not fit :(");
							System.out.println("Trying new frame pattern...");
							System.out.println("");							
							
							if (count < 50)
							{
								spotsLeftFrameStats[count] = spotsLeft;
								count++;
							}
							else
							{
								//Find average number of spots left with this board setup
								for (int m = 0; m < 50; m++)
								{
									
								}
								count = 0;
							}
							
							
							
							
							if (spotsLeft < 15 && j > 0)
							{
								//For display web ui purposes only								
								boardAndPieces[0] = board;
								boardAndPieces[1][0] = piecePlacedWhere;								
								return boardAndPieces;
							}
	
							//Resetting all temporary variables used
							coords = new int[2];
							//Clearing board of all pieces
							board = new int[16][16][4];							
							
							//Removing info about where pieces were placed
							//for (int k = startSpot; k < endSpot; k++)
							for (int k = startSpot; k < piecePlacedWhere.length; k++)
							{
								piecePlacedWhere[k][0] = 0;
								piecePlacedWhere[k][1] = 0;
							}
							
							//Restoring board setup with placements from earlier frames
							if (j == 0)
							{
								board = BackupSidePieces(boardBackup);
								addedPieces = new boolean[2][256];
								pieceTriedWhere = new int[256][56][2];
								availableSpots = StoreAvailableSpots(spots,	availableSpots, startSpot, endSpot);
							}
							else
							{							
								availableSpots = BackupFrameAvailableSpots(availableSpotsBackup);
								addedPieces = BackupFrameAddedPieces(addedPiecesBackup);
								board = BackupFrameBoard(boardBackup);
								//System.arraycopy(piecePlacedWhere, 0, piecePlacedWhereBackup, 0, piecePlacedWhereBackup.length);
								piecePlacedWhere = BackupFramePiecePlacedWhere(piecePlacedWhereBackup);								
							}
							
							resetCounter = 0;
	
							//Restoring board occupancies
							for (int k = 0; k < startSpot; k++) 
							{
								addedPieces[1][k] = true;
							}
	
							//Reset to first frame piece position
							//i = startSpot;
							
							//Need to replace piece 139 after clearing the board
							//Piece 139 is fixed in spot (7,8) from start
							int[] piece139 = {8,6,16,16};
							board[7][8] = piece139;
							addedPieces[1][138] = true;
							piecePlacedWhere[138][0] = 7;
							piecePlacedWhere[138][1] = 8;
	
						}
	
						//If we have a piece number, and have not reached maximum tries for this pattern
						if (i != -1 && resetCounter != 5 && resetCounter != 0) 
						{
							//Removing skip info for all skipped pieces since we want to try them again on the board
							for (int k = 0; k < addedPieces[0].length; k++) 
							{
								if (addedPieces[0][k] == true) 
								{
									addedPieces[0][k] = false;
									for (int l = 0; l < pieceTriedWhere[0].length; l++) 
									{
										pieceTriedWhere[k][l][0] = 0;
										pieceTriedWhere[k][l][1] = 0;
									}
								}
							}
	
							//System.out.println("Reached end of available pieces, trying now to place skipped pieces!");					
						}
					}
	
					coords = FindNextAvailableSpotRandomly(addedPieces,	pieceTriedWhere, piecePlacedWhere, board, spots, availableSpots, coords, spotsLeft, pieces, r, i, startSpot, endSpot, j);
					if (addedPieces[0][i] != true) 
					{
						//If we are working on side frame we align piece border side with border
						if (j == 0)
						{
							borderside = FindBorderSide(coords[0], coords[1]);
							AlignSidePieceWithBorder(pieces, borderside, pieceRotations, i);
						}
						
						pieceFits = CheckIfPieceMatchesSpot(coords[0], coords[1], board, pieces, i, borderside);					
						
						//Checking another spot side to make sure spot is empty
						if (pieceFits && (board[coords[0]][coords[1]][0] == 0 && board[coords[0]][coords[1]][2] == 0)) 
						{
							//Randomly select a different piece that also fits, to fully randomize placements
							i = PieceAlternativesForSpot(board, pieces, coords, pieceRotations, borderside, addedPieces, i);
							
							//Placing piece on board
							PlacePiece(coords[0], coords[1], board, pieces,	addedPieces, availableSpots, i,	piecePlacedWhere, pieceTriedWhere, startSpot, endSpot);
	
							//Clear all skipped pieces so that we can check all pieces again 
							//after a piece has been placed
							for (int k = 0; k < addedPieces[0].length; k++) 
							{
								addedPieces[0][k] = false;
							}
						} 
						else 
						{
							RotatePiece(pieces, pieceRotations, i);
							if (pieceRotations[i] == 3)
							{
								//If inner frames, we work with all remaining pieces
								i = FindNextAvailablePieceNumber(addedPieces, startSpot, 256);
								//skip that piece since it doesn't fit
								i = FindNextAvailablePieceNumber(addedPieces, i+1, 256);
							}
						}
					}
				}
			}
		}	
		boardAndPieces[0] = board;
		boardAndPieces[1][0] = piecePlacedWhere;
		return boardAndPieces;
	}
	
	public static int[][][] ResetFrameSpots(int[][][] boardBackup)
	{
		int[][][] board = new int[16][16][4];
		for (int j = 0; j < board.length; j++)
		{
			board[j] = boardBackup[j];
		}
		return board;
	}
	
	public static boolean PlaceCornerPieces(int[][][] board, int[][] pieces, boolean[][] addedPieces, int[][] piecePlacedWhere, int[][] availableSpots, int frameNumber, int[] pieceRotations, int i, int[][][] pieceTriedWhere, int start, int end) throws IOException
	{			
		Random generator = new Random();		
		boolean pieceFits = false;		
		//Initially no border side (any number > 3)
		int borderside = 666;
		int[][] cornerSpots = new int[4][2];
		int cornersPlaced = 0;
		boolean cornerPlaced = false;
		
		//Frame corner coordinates are set up so that they move clockwise
		//with the loop iterator starting from top left corner		
		cornerSpots[0][0] = 0 + frameNumber;
		cornerSpots[0][1] = 0 + frameNumber;
		
		cornerSpots[1][0] = 15 - frameNumber;
		cornerSpots[1][1] = 0 + frameNumber;
		
		cornerSpots[2][0] = 15 - frameNumber;
		cornerSpots[2][1] = 15 - frameNumber;
		
		cornerSpots[3][0] = 0 + frameNumber;
		cornerSpots[3][1] = 15 - frameNumber;
		
		while (i < 256)
		{
			//If we are on outer frame (sides), we need to align corner piece with borders
			if (frameNumber == 0)
			{
				boolean[] cornerPiece = new boolean[4];	
				//Random corner spot
				int r = generator.nextInt(4);
				
				//Trying all corner sides of frame
				for (int k = 0; k < 4; k++)
				{													
					
					for (int j = 0; j < k; j++)
					{
						RotatePiece(pieces, pieceRotations, r);
					}
					
					//Placing piece on board
					PlacePiece(cornerSpots[k][0], cornerSpots[k][1], board, pieces, addedPieces, availableSpots, r, piecePlacedWhere, pieceTriedWhere, start, end);
					addedPieces[1][r] = true;
					for (int l = 0; l < availableSpots.length; l++)
					{
						if (availableSpots[l][0] == cornerSpots[k][0] && availableSpots[l][1] == cornerSpots[k][1])
						{
							availableSpots[l][0] = 0;
							availableSpots[l][1] = 0;
						}
					}
					piecePlacedWhere[r][0] = cornerSpots[k][0];
					piecePlacedWhere[r][1] = cornerSpots[k][1];
					cornerPiece[r] = true;					
					
					for (int j = 0; j < 4; j++)
					{
						if (cornerPiece[j] == false)
						{
							//Found new available corner spot
							r = j;
							break;
						}
						if (j == 3)
						{
							//Found no available corner spots, all corners placed
							return true;
						}
					}
				}					
			}
			
				
			else
			{
				cornersPlaced = 0;
				int k = 0;
				
				//Find next available piece
				i = FindNextAvailablePieceNumber(addedPieces, 4, 256);				
				
				while (cornersPlaced < 4 && i < 256)
				{
					//Trying spot with all rotations of piece before moving to next
					for (int j = 0; j < 4; j++)
					{
						//We are adding pieces to the corners of an inner frame
						//Not a side piece = set to any number greater than 3
						pieceFits = CheckIfPieceMatchesSpot(cornerSpots[k][0], cornerSpots[k][1], board, pieces, i, borderside);
						if (!pieceFits)
						{							
							RotatePiece(pieces, pieceRotations, i);
						}											
					}
	
					//Checking another spot side to make sure spot is empty
					if (pieceFits && (board[cornerSpots[k][0]][cornerSpots[k][1]][0] == 0 && board[cornerSpots[k][0]][cornerSpots[k][1]][2] == 0)) 
					{
						//Placing piece on board
						PlacePiece(cornerSpots[k][0], cornerSpots[k][1], board, pieces, addedPieces, availableSpots, i, piecePlacedWhere, pieceTriedWhere, start, end);
						addedPieces[1][i] = true;	
						for (int l = 0; l < availableSpots.length; l++)
						{
							if (availableSpots[l][0] == cornerSpots[k][0] && availableSpots[l][1] == cornerSpots[k][1])
							{
								availableSpots[l][0] = 0;
								availableSpots[l][1] = 0;
							}
						}
						piecePlacedWhere[i][0] = cornerSpots[k][0];
						piecePlacedWhere[i][1] = cornerSpots[k][1];
						cornersPlaced++;
						cornerPlaced = true;
						
						if (cornersPlaced == 4)
						{
							//Placed all frame corner pieces, moving on to build the rest of the frame
							return true;
						}
						
						//Move to next corner spot
						k++;					
					}
					
					if (cornerPlaced)
					{
						//Find next available piece
						i = FindNextAvailablePieceNumber(addedPieces, 4, 256);
						cornerPlaced = false;
					}
					else if (i < 255)
					{
						//Find next available piece
						i = FindNextAvailablePieceNumber(addedPieces, i+1, 256);
					}
					else
					{
						break;
					}
				}				
			}			
			
			//Checking if we reached the end of pieces to check
			if (i >= 255 && cornersPlaced < 4)
			{
				System.out.println("");
				System.out.println("Could not fill all corner spots in frame " + (frameNumber+1) + "...");
				System.out.println("");
				
				//Need to start swapping pieces in previous frame that face this corner to get new side patterns
				//Coming soon...
				return false;
			}		
		}
		return false;
	}		
	
	public static int[][][] BackupFrameBoard(int[][][] board)
	{
		int[][][] boardBackup = new int[16][16][4];
		
		for (int j = 0; j < board.length; j++)
		{
			for (int k = 0; k < board.length; k++)
			{
				boardBackup[j][k] = board[j][k];
			}
		}
		
		return boardBackup;
	}
	
	public static boolean[][] BackupFrameAddedPieces(boolean[][] addedPieces)
	{
		boolean[][] addedPiecesBackup = new boolean[2][256];		
		
		for (int j = 0; j < 256; j++)
		{		
			addedPiecesBackup[0][j] = addedPieces[0][j];
			addedPiecesBackup[1][j] = addedPieces[1][j];
		}
		
		return addedPiecesBackup;
	}
	
	public static int[][] BackupFrameAvailableSpots(int[][] availableSpots)
	{
		int[][] availableSpotsBackup = new int[56][2];
		
		for (int j = 0; j < 56; j++)
		{		
			availableSpotsBackup[j][0] = availableSpots[j][0];
			availableSpotsBackup[j][1] = availableSpots[j][1];			
		}
		
		return availableSpotsBackup;
	}
	
	public static int[][] BackupFramePiecePlacedWhere(int[][] piecePlacedWhere)
	{
		int[][] piecePlacedWhereBackup = new int[256][2];
		
		for (int j = 0; j < 256; j++)
		{		
			piecePlacedWhereBackup[j][0] = piecePlacedWhere[j][0];
			piecePlacedWhereBackup[j][1] = piecePlacedWhere[j][1];
			
		}
		
		return piecePlacedWhereBackup;
	}
	
	public static int[][][] BackupSidePieces(int[][][] board)
	{
		//Keeping corner positions
		int[] corner1 = board[0][0];
		int[] corner2 = board[15][0];
		int[] corner3 = board[0][15];
		int[] corner4 = board[15][15];
		
		board = new int[16][16][4];
		
		//Restoring corner positions
		board[0][0] = corner1;
		board[15][0] = corner2;
		board[0][15] = corner3;
		board[15][15] = corner4;
		
		return board;
	}
	
	public static int[][] RotatePiece(int[][] pieces, int[] pieceRotations, int i)
	{
		int side1 = pieces[i][0];
		int side2 = pieces[i][1];
		int side3 = pieces[i][2];
		int side4 = pieces[i][3];
		
		pieces[i][0] = side4;
		pieces[i][1] = side1;
		pieces[i][2] = side2;
		pieces[i][3] = side3;			
		
		pieceRotations[i] = pieceRotations[i] + 1;
		if (pieceRotations[i] > 3)
		{
			pieceRotations[i] = 0;
		}
		
		return pieces;
	}
	
}

